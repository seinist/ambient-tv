#pragma once
#include <pthread.h>
#include <stdbool.h>
#include <stdlib.h>
#include <time.h>
#include <errno.h>

/* A timed-out job retains its opaque context. Worker ownership is independent
 * of the module activation caller; never cancel a Vulkan driver call. */
struct ambient_init_work {
    pthread_mutex_t lock;
    pthread_cond_t cv;
    bool done, abandoned;
    void (*operation)(void *);
    void *opaque;
};
static inline void ambient_init_work_free(struct ambient_init_work *w)
{
    pthread_cond_destroy(&w->cv);
    pthread_mutex_destroy(&w->lock);
    free(w);
}
static inline void *ambient_init_worker(void *arg)
{
    struct ambient_init_work *w=arg;
    w->operation(w->opaque);
    pthread_mutex_lock(&w->lock);
    w->done=true;
    bool abandoned=w->abandoned;
    pthread_cond_signal(&w->cv);
    pthread_mutex_unlock(&w->lock);
    if(abandoned)ambient_init_work_free(w);
    return NULL;
}
static inline bool ambient_init_retire(void *opaque,void (*operation)(void *),unsigned timeout_ms)
{
    struct timespec deadline;
    if(clock_gettime(CLOCK_MONOTONIC,&deadline))return false;
    deadline.tv_sec+=timeout_ms/1000;
    deadline.tv_nsec+=(long)(timeout_ms%1000)*1000000L;
    if(deadline.tv_nsec>=1000000000L){deadline.tv_sec++;deadline.tv_nsec-=1000000000L;}
    struct ambient_init_work *w=calloc(1,sizeof(*w));
    if(!w)return false;
    if(pthread_mutex_init(&w->lock,NULL)){free(w);return false;}
    pthread_condattr_t ca;
    if(pthread_condattr_init(&ca)){pthread_mutex_destroy(&w->lock);free(w);return false;}
    int err=pthread_condattr_setclock(&ca,CLOCK_MONOTONIC);
    if(!err)err=pthread_cond_init(&w->cv,&ca);
    pthread_condattr_destroy(&ca);
    if(err){pthread_mutex_destroy(&w->lock);free(w);return false;}
    w->opaque=opaque;w->operation=operation;
    pthread_attr_t attr;
    if(pthread_attr_init(&attr)){ambient_init_work_free(w);return false;}
    err=pthread_attr_setdetachstate(&attr,PTHREAD_CREATE_DETACHED);
    pthread_t thread;
    if(!err)err=pthread_create(&thread,&attr,ambient_init_worker,w);
    pthread_attr_destroy(&attr);
    if(err){ambient_init_work_free(w);return false;}
    pthread_mutex_lock(&w->lock);
    while(!w->done){
        err=pthread_cond_timedwait(&w->cv,&w->lock,&deadline);
        if(err&&!w->done){w->abandoned=true;pthread_mutex_unlock(&w->lock);return false;}
    }
    pthread_mutex_unlock(&w->lock);
    ambient_init_work_free(w);
    return true;
}
