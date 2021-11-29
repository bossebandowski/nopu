#include <stdint.h>

void cop_busy_wait()
{
    register uint32_t state __asm__("18") = 1;
    while (state)
    {
        //printf("state: %lu\n", state);
        asm(".word 0x3640083" // unpredicated COP_READ to COP0 with FUNC = 00001, RA = 00000, RD = 10010
            : "=r"(state)
            :
            : "18");
    }
    //printf("state: %lu\n", state);
}

void cop_reset()
{
    asm(".word 0x3400001"); // unpredicated COP_WRITE to COP0 with FUNC = 00000, RA = 00000, RB = 00000
    cop_busy_wait();

}

void cop_run()
{
    asm(".word 0x3440001"); // unpredicated COP_WRITE to COP0 with FUNC = 00010, RA = 00000, RB = 00000
    cop_busy_wait();
}

int32_t cop_get_res()
{
    register uint32_t result __asm__("19") = 11;
    asm(".word 0x3660203"
        : "=r"(result)
        :
        : "19");

    // pref     01101
    // regD     10011
    // regA     00000
    // func     00100
    // post     0000011

    return result;
}

void cop_mem_w(int addr, int val)
{
    register uint32_t addrReg __asm__("16") = addr;
    register uint32_t valReg __asm__("17") = val;

    asm(".word 0x3470881"
        :
        : "r"(addrReg), "r"(valReg));

    // pref     01101
    // func     00011
    // regA     10000
    // regB     10001
    // post     0000001

    cop_busy_wait();
}

void cop_config(uint8_t layer, uint8_t config_id, int32_t val) {
    int32_t addr = layer << 8 | config_id;
    printf("%x\n", layer);
    printf("%x\n", config_id);
    printf("%x\n", addr);

    register uint32_t addrReg __asm__("16") = addr;
    register uint32_t valReg __asm__("17") = val;

    asm(".word 0x3470881"
        :
        : "r"(addrReg), "r"(valReg));

    // pref     01101
    // func     00011
    // regA     10000
    // regB     10001
    // post     0000001
}

void cop_mem_r(int addr)
{
    register uint32_t addrReg __asm__("16") = addr;

    asm(".word 0x34B0001"
        :
        : "r"(addrReg));

    // pref     01101
    // func     00101
    // regA     10000
    // regB     00000
    // post     0000001

    cop_busy_wait();
}