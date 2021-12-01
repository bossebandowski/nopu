#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>

#include <counter.h>
#include <cop.h>
#include <parameters.h>
#include <images.h>

#include <machine/patmos.h>

#include "ethlib/icmp.h"
#include "ethlib/icmp.h"
#include "ethlib/arp.h"
#include "ethlib/mac.h"
#include "ethlib/udp.h"
#include "ethlib/ipv4.h"
#include "ethlib/eth_mac_driver.h"

unsigned int PCKG_SIZE = 1024;
unsigned int UDP_PORT = 5005;
unsigned int rx_addr = 0x000;
unsigned int tx_addr = 0x800;
int32_t img[3072];
unsigned char HOST_IP[4];	


const uint fc = 2;
const uint conv = 3;
const uint pool = 4;
const uint fc_requantize = 12;
const uint fc_write_bias = 11;

void print_general_info(){
	printf("\nGeneral network info:\n");
	printf("  MAC: ");
	mac_print_my_mac();
	printf("\n  IP: ");
	ipv4_print_my_ip();
	printf("\n  UDP port %d", UDP_PORT);
    printf("\n");
	return;
}

void send_res(int res) {
    unsigned char buffer[1];
    unsigned char msg[] = {res};
    udp_t packet;
    packet.data = buffer;

    udp_build_packet(&packet, my_ip, HOST_IP, UDP_PORT, UDP_PORT, msg, 1);
    udp_send_packet(tx_addr, rx_addr, packet, 100000);
    printf("sending udp packet to %u.%u.%u.%u:%u\n", HOST_IP[0], HOST_IP[1], HOST_IP[2], HOST_IP[3], UDP_PORT);
    return;
}

void receive_img(){
	enum eth_protocol packet_type;
	unsigned char ans;
	unsigned char udp_data[PCKG_SIZE];
	unsigned char source_ip[4];	
	unsigned char destination_ip[4];
	unsigned short int destination_port;
	eth_mac_initialize();
	arp_table_init();
    int i = 0;

	while (i<3){
		eth_mac_receive(rx_addr, 0);
		packet_type = mac_packet_type(rx_addr);
		switch (packet_type) {
		case UNSUPPORTED:
		break;
		case ICMP:
			ans = icmp_process_received(rx_addr, tx_addr);
		break;
		case UDP:
			ipv4_get_source_ip(rx_addr, source_ip);
			ipv4_get_destination_ip(rx_addr, destination_ip);
			udp_get_source_port(rx_addr);
			udp_get_destination_port(rx_addr);
			udp_get_checksum(rx_addr);

			if(ipv4_verify_checksum(rx_addr) == 1 && udp_verify_checksum(rx_addr) == 1 && ipv4_compare_ip(my_ip, destination_ip) == 1){
                printf(".");
                for (int i = 0; i < 4; i++) {
                    HOST_IP[i] = source_ip[i];
                }
				destination_port = udp_get_destination_port(rx_addr);
				if(destination_port == UDP_PORT){
                    udp_get_data(rx_addr, udp_data, udp_get_data_length(rx_addr));
                    udp_data[udp_get_data_length(rx_addr)] = '\0';
                    for (int idx = 0; idx < PCKG_SIZE; idx++) {
                        img[i * PCKG_SIZE + idx] = udp_data[idx];
                    }
                    i++;
				}else{
					printf("Wrong port.\n");
				}
			}						
		break;
		case ARP:
			ans = arp_process_received(rx_addr, tx_addr);
		break;
		default:
			printf("ERROR!\n");
		break;
		}
	}


    printf("\n");    
	return;
}

void load_nn_cifar_10()
{
    cop_config(0, 8, 6);

    cop_config(0, 0, conv);
    cop_config(1, 0, pool);
    cop_config(2, 0, conv);
    cop_config(3, 0, pool);
    cop_config(4, 0, fc);
    cop_config(5, 0, fc);

    cop_config(0, 1, 0);
    cop_config(1, 1, 0);
    cop_config(2, 1, 0);
    cop_config(3, 1, 0);
    cop_config(4, 1, fc_requantize);
    cop_config(5, 1, fc_write_bias);

    cop_config(0, 2, &param_7_w_conv);
    cop_config(1, 2, 0);
    cop_config(2, 2, &param_10_w_conv);
    cop_config(3, 2, 0);
    cop_config(4, 2, &param_14_w_fc);
    cop_config(5, 2, &param_16_w_fc);

    cop_config(0, 3, &param_2_b);
    cop_config(1, 3, 0);
    cop_config(2, 3, &param_3_b);
    cop_config(3, 3, 0);
    cop_config(4, 3, &param_4_b);
    cop_config(5, 3, &param_5_b);

    cop_config(0, 4, 0x20030310);
    cop_config(1, 4, 0x1e220f10);
    cop_config(2, 4, 0x0f031010);
    cop_config(3, 4, 0x0d220610);
    cop_config(4, 4, 576);
    cop_config(5, 4, 64);

    cop_config(0, 5, 14400);
    cop_config(1, 5, 3600);
    cop_config(2, 5, 2704);
    cop_config(3, 5, 576);
    cop_config(4, 5, 64);
    cop_config(5, 5, 12);

    cop_config(0, 6, &ms_0);
    cop_config(1, 6, 0);
    cop_config(2, 6, &ms_1);
    cop_config(3, 6, 0);
    cop_config(4, 6, &ms_2);
    cop_config(5, 6, 0);
}

void load_img()
{
    cop_config(0, 7, &img[0]);
}

void read_raw_outputs()
{
    for (int i = 0; i < 10; i++)
    {
        cop_mem_r(i);
        printf("%d: %lx\n", i, cop_get_res());
    }
}

void print_intermediate_layer_head(bool even, int offset, int count)
{
    for (int i = 0; i < count; i++)
    {
        if (even) {
            cop_mem_r(i + 16384 + offset);
        }
        else {
            cop_mem_r(i + offset);
        }

        printf("%d: %ld\n", i + offset, cop_get_res());
    }
}

int run_inf() {
    cop_run();
    return cop_get_res();
}

void run_emulator() {
    // load nn parameters into desired memory space. In the future, this will hopefully be copying from flash to sram
    int res;
    int hwExecTime;
    load_nn_cifar_10();
    load_img();

    for (int id = 0; id < 10; id++) {
        // img = images[id];            FIX THIS   
        cntReset();
        res = run_inf();
        hwExecTime = cntRead();
        printf("EXPECTED %x, RETURNED %u\n", results[id], res);
    }

    printf("================================\n");
    printf("gross execution time per inference (including img load): %d\n", hwExecTime);
}

void run_fpga() {
    int res;
    printf("configuring network...");
    load_nn_cifar_10();
    printf("done\n");
    printf("waiting for input via ethernet\n");
    

    for (;;) {
        receive_img();
        load_img();
        res = run_inf();
        send_res(res);
        printf("RESULT: %u\n", res);
    }
}

int main(int argc, char **argv)
{
    run_fpga();
    // run_emulator();

    return 0;
}
