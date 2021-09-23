#include "counter.h"
#include "collection.h"
#include "parameters.h"

// IO device addresses
volatile _IODEV int *IO_PTR_ACC = (volatile _IODEV int *) 0xf00c0000;

#define ADR_ACCELERATOR_BASE                        0xf00c0000

#define ADR_ACCELERATOR_INPUT                       *((volatile _IODEV unsigned int *) (ADR_ACCELERATOR_BASE + 0x0))
#define ADR_ACCELERATOR_STATUS                      *((volatile _IODEV unsigned int *) (ADR_ACCELERATOR_BASE + 0x4))
#define ADR_ACCELERATOR_RESULT                      *((volatile _IODEV unsigned int *) (ADR_ACCELERATOR_BASE + 0x8))
#define ADR_ACCELERATOR_MEMORY_TEST_READ            *((volatile _IODEV unsigned int *) (ADR_ACCELERATOR_BASE + 0xC))
#define ADR_ACCELERATOR_SET_MEM_ADDR                *((volatile _IODEV unsigned int *) (ADR_ACCELERATOR_BASE + 0x10))

void fillNeuralNetwork(bool v) {
    if (v) {
        printf("Loading weights and biases into NN\n");
    }
    // transition to network load state
    ADR_ACCELERATOR_INPUT = 0;
    
    if (v) {
        printf("transitioned to state: %d \n", ADR_ACCELERATOR_STATUS);
        printf("Parameter transfer to accelerator has started\n");
        printf("Transfering weights\n");
    }
    
    for(int i = 0; i < 78400; i++)
    {
        ADR_ACCELERATOR_INPUT = weights_1[i];
    }
    
    for(int i = 0; i < 1000; i++)
    {
        ADR_ACCELERATOR_INPUT = weights_2[i];
    }
    if (v) {
        printf("Weights transferred successfully\n");
        printf("Transfering biases\n");
    }
    for(int i = 0; i < 100; i++)
    {
        ADR_ACCELERATOR_INPUT = biases_1[i];
    }
    
    for(int i = 0; i < 10; i++)
    {
        ADR_ACCELERATOR_INPUT = biases_2[i];
    }
    if (v) {
        printf("Biases transferred successfully\n");
    }
}

void loadNetworkCheck() {
    printf("init state: %d \n", ADR_ACCELERATOR_STATUS);

    fillNeuralNetwork(true);

    printf("state after network load: %d \n", ADR_ACCELERATOR_STATUS);
    int rsp = readFromMem(784);
    printf("Expected: %d, Read: %d \n",weights_1[0], rsp);
    rsp = readFromMem(10000);
    printf("Expected: %d, Read: %d \n",weights_1[9216], rsp);
    rsp = readFromMem(79183);
    printf("Expected: %d, Read: %d \n",weights_1[78399], rsp);
    rsp = readFromMem(79184);
    printf("Expected: %d, Read: %d \n",weights_2[0], rsp);
    rsp = readFromMem(79185);
    printf("Expected: %d, Read: %d \n",weights_2[1], rsp);
    rsp = readFromMem(80182);
    printf("Expected: %d, Read: %d \n",weights_2[998], rsp);
    rsp = readFromMem(80183);
    printf("Expected: %d, Read: %d \n",weights_2[999], rsp);
    rsp = readFromMem(80184);
    printf("Expected: %d, Read: %d \n",biases_1[0], rsp);
    rsp = readFromMem(80292);
    printf("Expected: %d, Read: %d \n",biases_2[8], rsp);
    rsp = readFromMem(80293);
    printf("Expected: %d, Read: %d \n",biases_2[9], rsp);

    
    printf("==================\n");
    printf("network check done\n");
    printf("==================\n");

}

void loadImg(int imageIndex, bool v) {
    // transition to infload
    ADR_ACCELERATOR_INPUT = 1;
    if (v) {
        printf("inf load state: %d \n", ADR_ACCELERATOR_STATUS);
    }
    for(int i = 0; i < 784; i++) {
        ADR_ACCELERATOR_INPUT = images[imageIndex][i];
        //ADR_ACCELERATOR_INPUT = picture[i];
    }
}

void loadInfCheck() {
    // check initial state (should be idle after network load, otherwise nonn)
    printf("init state: %d \n", ADR_ACCELERATOR_STATUS);
    // check state (should be infload)
    printf("loading img...\n");
    //loadImg(true);

    //printf("done\n");

    for (int i = 0; i < 30; i++) {
        printf("Expected: %d, Read: %d \n", picture[i*20], readFromMem(i*20));
    }

    printf("Expected: %d, Read: %d \n", picture[783], readFromMem(783));


    printf("==================\n");
    printf("img check done\n");
    printf("==================\n");
    printf("state after inf load: %d \n", ADR_ACCELERATOR_STATUS);
}


int readFromMem(int addr) {
    printf("checking value at address %d \n", addr);
    ADR_ACCELERATOR_SET_MEM_ADDR = addr;
    int val = ADR_ACCELERATOR_MEMORY_TEST_READ;
    val = ADR_ACCELERATOR_MEMORY_TEST_READ;
    return val;
}

int calculateNNCPU(int aImageIndex)
{
    cntReset();
    int layer1[100];
    int layer2[10];

    //Execution time starts from here
    // First layer
    // Calculate neuron values
    int weightIndex = 0;
    for(int i = 0; i < 100; i++)
    {
        for(int p = 0; p < 784; p++)
        {
            layer1[i] += images[aImageIndex][p] * weights_1[weightIndex];
            weightIndex ++;
        }
    }
    // Add biases
    for(int i = 0; i < 100; i++)
    {
        layer1[i] += biases_1[i];
    }
    // Apply relu
    for(int i = 0; i < 100; i++)
    {
        if(layer1[i] <= 0)
        {
            layer1[i] = 0;
        }
    }

    // Second layer
    // Calculate neuron values
    weightIndex = 0;
    for(int i = 0; i < 10; i++)
    {
        for(int fl = 0; fl < 100; fl ++)
        {
            layer2[i] += layer1[fl] * weights_2[weightIndex];
            weightIndex ++;
        }
    }
    // Add biases
    for(int i = 0; i < 10; i++)
    {
        layer2[i] += biases_2[i];
    }

    // Find maximum
    int resultnum = layer2[0];
    int result = 0;
    for(int i = 0; i < 10; i++)
    {
        if(layer2[i] > resultnum)
        {
            result = i;
            resultnum = layer2[i];
        }
    }

    executionTimes[aImageIndex] = cntRead();
    return result;
}