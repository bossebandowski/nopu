#include <parameters.h>
#include <cop.h>

const uint fc = 2;
const uint conv = 3;
const uint pool = 4;
const uint fc_requantize = 12;
const uint fc_write_bias = 11;

void load_nn_cifar_10()
{
    // number of layers
    cop_config(0, 8, 6);

    // layer types
    cop_config(0, 0, conv);
    cop_config(1, 0, pool);
    cop_config(2, 0, conv);
    cop_config(3, 0, pool);
    cop_config(4, 0, fc);
    cop_config(5, 0, fc);

    // fc activations
    cop_config(0, 1, 0);
    cop_config(1, 1, 0);
    cop_config(2, 1, 0);
    cop_config(3, 1, 0);
    cop_config(4, 1, fc_requantize);
    cop_config(5, 1, fc_write_bias);

    // weight addresses
    cop_config(0, 2, &param_7_w_conv);
    cop_config(1, 2, 0);
    cop_config(2, 2, &param_10_w_conv);
    cop_config(3, 2, 0);
    cop_config(4, 2, &param_14_w_fc);
    cop_config(5, 2, &param_16_w_fc);

    // bias addresses
    cop_config(0, 3, &param_2_b);
    cop_config(1, 3, 0);
    cop_config(2, 3, &param_3_b);
    cop_config(3, 3, 0);
    cop_config(4, 3, &param_4_b);
    cop_config(5, 3, &param_5_b);

    // input shapes
    cop_config(0, 4, 0x20030310);
    cop_config(1, 4, 0x1e220f10);
    cop_config(2, 4, 0x0f031010);
    cop_config(3, 4, 0x0d220610);
    cop_config(4, 4, 576);
    cop_config(5, 4, 64);

    // output shapes
    cop_config(0, 5, 14400);
    cop_config(1, 5, 3600);
    cop_config(2, 5, 2704);
    cop_config(3, 5, 576);
    cop_config(4, 5, 64);
    cop_config(5, 5, 12);

    // requantization factors
    cop_config(0, 6, &ms_0);
    cop_config(1, 6, 0);
    cop_config(2, 6, &ms_1);
    cop_config(3, 6, 0);
    cop_config(4, 6, &ms_2);
    cop_config(5, 6, 0);
}