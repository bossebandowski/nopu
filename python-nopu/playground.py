import socket
import tensorflow as tf
import numpy as np
import time
import sys

def load_cifar10():
    cifar10 = tf.keras.datasets.cifar10
    (train_images, train_labels), (test_images, test_labels) = cifar10.load_data()
    
    return (train_images, train_labels), (test_images, test_labels)

def send(ip, port, payload, s):
    time.sleep(1)
    s.sendto(payload, (ip, port))

def receive(s):
    data, address = s.recvfrom(1024)
    return data

(_, _), (test_images, test_labels) = load_cifar10()

img_idx = int(sys.argv[1])
img0 = test_images[img_idx]
label = test_labels[img_idx]
TARGET_IP = "192.168.24.50"
TARGET_PORT = 5005
MESSAGE = "HEY"
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)


for i in range(3):
    send(TARGET_IP, TARGET_PORT, img0.flatten()[i*1024:(i+1)*1024].tobytes(), sock)

res = receive(sock)

print("============")
print(f"expected {int(label)}, got {res}")