#define _POSIX_C_SOURCE 200809L
#include "terminal_init_retirement.h"
#include <assert.h>
#include <stdatomic.h>
#include <stdio.h>
static void quick(void *p){atomic_store((atomic_bool *)p,true);}
struct blocked { atomic_bool release,done; };
static void slow(void *p){
    struct blocked *b=p;
    struct timespec t={0,1000000};
    while(!atomic_load(&b->release))nanosleep(&t,NULL);
    atomic_store(&b->done,true);
}
int main(void){
    atomic_bool done=false;
    assert(ambient_init_retire(&done,quick,1000)&&atomic_load(&done));
    struct blocked b={0};
    assert(!ambient_init_retire(&b,slow,10));
    assert(!atomic_load(&b.done));
    atomic_store(&b.release,true);
    struct timespec t={0,1000000};
    for(unsigned i=0;i<1000&&!atomic_load(&b.done);i++)nanosleep(&t,NULL);
    assert(atomic_load(&b.done));
    puts("Bounded partial Vulkan initialization retirement passed");
}
