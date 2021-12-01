#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>

#include <counter.h>
#include <network-configs.h>

#include <machine/patmos.h>

#include "ethlib/icmp.h"
#include "ethlib/icmp.h"
#include "ethlib/arp.h"
#include "ethlib/mac.h"
#include "ethlib/udp.h"
#include "ethlib/ipv4.h"
#include "ethlib/eth_mac_driver.h"

unsigned int PCKG_SIZE = 1024;
const uint8_t NUM_BATS = 3;

unsigned int UDP_PORT = 5005;
unsigned int rx_addr = 0x000;
unsigned int tx_addr = 0x800;
int32_t img[3072];
unsigned char HOST_IP[4];	
uint8_t SEQ = 0;
uint8_t BAT = 0;



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
    unsigned char buffer[2];
    unsigned char msg[] = {SEQ, res};
    udp_t packet;
    packet.data = buffer;

    udp_build_packet(&packet, my_ip, HOST_IP, UDP_PORT, UDP_PORT, msg, 2);
    udp_send_packet(tx_addr, rx_addr, packet, 100000);
    printf("sending response to %u.%u.%u.%u:%u\n", HOST_IP[0], HOST_IP[1], HOST_IP[2], HOST_IP[3], UDP_PORT);
    return;
}

void send_ack() {
    unsigned char buffer[2];
    unsigned char msg[] = {SEQ, BAT};
    udp_t packet;
    packet.data = buffer;
    udp_build_packet(&packet, my_ip, HOST_IP, UDP_PORT, UDP_PORT, msg, 2);
    udp_send_packet(tx_addr, rx_addr, packet, 100000);
    printf("sending SEQ %u BAT %u to %u.%u.%u.%u:%u\n", SEQ, BAT, HOST_IP[0], HOST_IP[1], HOST_IP[2], HOST_IP[3], UDP_PORT);
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
    BAT = 0;

	while (BAT<NUM_BATS){
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
                for (int i = 0; i < 4; i++) {
                    HOST_IP[i] = source_ip[i];
                }
				destination_port = udp_get_destination_port(rx_addr);
				if(destination_port == UDP_PORT){
                    udp_get_data(rx_addr, udp_data, udp_get_data_length(rx_addr));
                    udp_data[udp_get_data_length(rx_addr)] = '\0';
                    for (int idx = 0; idx < PCKG_SIZE; idx++) {
                        img[BAT * PCKG_SIZE + idx] = udp_data[idx];
                    }
                    BAT++;
                    send_ack();
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

    BAT = 0;
    SEQ++;
	return;
}



void load_img()
{
    cop_config(0, 7, &img[0]);
}

int run_inf() {
    cop_run();
    return cop_get_res();
}

void run_fpga() {
    int res;
    printf("configuring network...");
    load_nn_cifar_10();
    printf("done\n");
    printf("ready to rumble\n");
    

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

    return 0;
}
