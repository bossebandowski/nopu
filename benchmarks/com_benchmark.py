import socket
import tensorflow as tf
import numpy as np
import time
import sys
import pandas as pd

NUM_ROUNDS = 1
NUM_IMGS = 10000

def get_host_ip():
    name = socket.gethostname()
    addr = socket.gethostbyname(name)
    return addr

def send(ip, port, payload, s):
    s.sendto(payload, (ip, port))

NUM_BAT = 3
TARGET_IP = get_host_ip()
HOST_IP = get_host_ip()
PORT = 5005
PACKET_SIZE = 1024
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((HOST_IP, PORT))

def send_img_chunk(img, chunk):
    send(TARGET_IP, PORT, img.flatten()[chunk*PACKET_SIZE:(chunk+1)*PACKET_SIZE].tobytes(), sock)
    # measured average time to receive ack
    time.sleep(0.003)

def inf(img):
    for i in range(NUM_BAT):
        send_img_chunk(img, i)

def main():
    # load dataset
    (_, _), (test_images, test_labels) = tf.keras.datasets.cifar10.load_data()
    # send images forever to measure power usage
    while True:
        # iterate over the entire dataset
        print("go")
        for idx in range(NUM_IMGS): 
            input_img = np.asarray([test_images[idx]])
            inf(test_images[idx])
            time.sleep(0.018)

if __name__ == "__main__":
    main()
