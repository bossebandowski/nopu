#include <machine/patmos.h>
#include <stdio.h>
#include <stdbool.h>
#include <unistd.h>
#include "accelerator/neuralNetwork.h"

int main() 
{
    printf("Program started\n");
    loadNetworkCheck();

    int result;
    int hwExecTime = 0;
    int errorCounter = 0;
    printf("==========================\n");
    printf("Starting inference test...\n");
    printf("==========================\n");

    for(int i = 0; i < 100; i++)
    {
        loadImg(i, false);
        while(ADR_ACCELERATOR_STATUS != 2)
        {
            result = ADR_ACCELERATOR_RESULT;
        }
        if(result == results[i])
        {
            printf("CORRECT. Expected %d and got %d\n", results[i],result);
        }
        else
        {
            printf("INCORRECT. Expected %d and got %d at image %d\n", results[i],result, i);
            errorCounter++;
        }
    }
    
    printf("100 tests done. Number of errors: %d \n", errorCounter);
    printf("Measuring execution time with no printf...\n");
    cntReset();
    loadImg(0, false);
    while(ADR_ACCELERATOR_STATUS != 2)
    {
        result = ADR_ACCELERATOR_RESULT;
    }
    hwExecTime = cntRead();
    printf("Execution time was %d\n", hwExecTime);
    printf("==========================\n");

    printf("Run software NN tests and measuring execution times (no printf-s)...\n");

    for(int i = 0; i< 100; i++)
    {
        calculateNNCPU(i);
    }
    printf("100 tests done!");

    //Calculating avarage runtime of sw runs
    int avarageRuntime = 0;

    for(int i = 0; i< 100; i++)
    {
        avarageRuntime += executionTimes[i];
    }
    avarageRuntime = avarageRuntime / 100;
    printf("The avarage software runtime was: %d\n", avarageRuntime);
}
