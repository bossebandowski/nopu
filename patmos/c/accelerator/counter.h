#include <machine/spm.h>
#include <machine/rtc.h>

// Counter makes possible to measure execution times
//Usage:
// -Reset it by the cntReset() function at the beginning of the time interval
// -Read out its current value by cntRead() or by cntReadMicros()

volatile _IODEV int *IO_PTR_CNT = (volatile _IODEV int *) 0xf00b0000;

void cntReset()
{
    *IO_PTR_CNT = 0; 
}

int cntRead()
{
    return (*IO_PTR_CNT);
}

double cntReadMicros()
{
    int cycles = cntRead();
    double period = 1000000.0 / (double) get_cpu_freq(); //[usec]
    return (cycles * period);
}