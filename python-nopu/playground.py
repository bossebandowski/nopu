import socket
import tensorflow as tf
import numpy as np
import time
import sys
import cv2

CLASSES = ["AIRPLANE", "AUTOMOBILE", "BIRD", "CAT", "DEER", "DOG", "FROG", "HORSE", "SHIP", "TRUCK"]

def load_cifar10():
    cifar10 = tf.keras.datasets.cifar10
    (train_images, train_labels), (test_images, test_labels) = cifar10.load_data()
    
    return (train_images, train_labels), (test_images, test_labels)

def save_example_images():
    for i in range(10):
        cv2.imwrite(f"cifar10_{i}.jpg", test_images[i])

def send(ip, port, payload, s):
    time.sleep(1)
    s.sendto(payload, (ip, port))

def receive(s):
    data, _ = s.recvfrom(1024)
    return data

(_, _), (test_images, test_labels) = load_cifar10()

img_idx = int(sys.argv[1])
img0 = test_images[img_idx]
label = test_labels[img_idx]
TARGET_IP = "192.168.24.50"
HOST_IP = "192.168.24.45"
PORT = 5005
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((HOST_IP, PORT))
sock.settimeout(5)
for i in range(3):
    send(TARGET_IP, PORT, img0.flatten()[i*1024:(i+1)*1024].tobytes(), sock)

res = ''.join(format(x, '02x') for x in receive(sock))
print("============")
print(f"expected {CLASSES[int(label)]}, got {CLASSES[int(res)]}")

save_example_images()