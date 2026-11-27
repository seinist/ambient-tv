#include "ambient_metadata_wire.h"
#include <assert.h>
#include <sys/socket.h>
#include <unistd.h>
#include <stdio.h>
/* Same golden wire header as the Android instrumentation. */
int main(void)
{
    const uint8_t golden[48]={
        0x41,0x46,0x4d,0x33,0,0,0,2,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,7,0,0,0,0,0,0x12,0xd4,0x50,
        0,0,0,0,0,0,0,42};
    uint8_t out[48],in[48],ack[8]={0x41,0x46,0x41,0x31,0,0,0,7};
    ambient_metadata_header(out,2,0,0,0,7,1234000,42);
    assert(memcmp(out,golden,sizeof(out))==0);
    int fd[2];assert(socketpair(AF_UNIX,SOCK_STREAM,0,fd)==0);
    assert(write(fd[0],out,13)==13);
    assert(write(fd[0],out+13,35)==35);
    for(unsigned i=0;i<sizeof(in);i++)assert(read(fd[1],in+i,1)==1);
    assert(memcmp(in,golden,sizeof(in))==0);
    assert(write(fd[1],ack,8)==8);
    for(unsigned i=0;i<8;i++)assert(read(fd[0],ack+i,1)==1);
    assert(ambient_metadata_ack_valid(ack,7));
    assert(!ambient_metadata_ack_valid(ack,8));
    ack[0]=0;assert(!ambient_metadata_ack_valid(ack,7));
    ambient_metadata_header(out,2,0,0,0,8,1234000,43);
    assert(out[31]==8&&out[47]==43&&!memcmp(out+32,golden+32,8));
    ambient_metadata_header(out,0,250000,100000,8,8,1234000,43);
    assert(out[7]==0&&out[27]==8&&out[47]==43);
    ambient_metadata_header(out,2,0,0,0,0,-1,0);
    for(unsigned i=32;i<40;i++)assert(out[i]==255);
    close(fd[0]);close(fd[1]);puts("AFM3 native wire and Unix socket contract passed");
}
