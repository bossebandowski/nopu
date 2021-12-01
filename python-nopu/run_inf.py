import socket
import tensorflow as tf
import numpy as np
import time
import sys
import cv2

def load_cifar10():
    cifar10 = tf.keras.datasets.cifar10
    (train_images, train_labels), (test_images, test_labels) = cifar10.load_data()
    
    return (train_images, train_labels), (test_images, test_labels)

def save_example_images():
    for i in range(10):
        cv2.imwrite(f"cifar10_{i}.jpg", test_images[i])

def send(ip, port, payload, s):
    s.sendto(payload, (ip, port))

def wait_for_ack(s):
    data, _ = s.recvfrom(1024)
    return data[0], data[1]

def receive(s):    
    try:
        data, _ = s.recvfrom(1024)
        return data
    except Exception:
        return None


CLASSES = ["AIRPLANE", "AUTOMOBILE", "BIRD", "CAT", "DEER", "DOG", "FROG", "HORSE", "SHIP", "TRUCK"]
SEQ = 0
BAT = 0
NUM_BAT = 3
MAX_TRIES = 3
(_, _), (test_images, test_labels) = load_cifar10()
TARGET_IP = "192.168.24.50"
HOST_IP = "192.168.24.45"
PORT = 5005
PACKET_SIZE = 1024
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((HOST_IP, PORT))
sock.settimeout(1)

def send_img_chunk(img, chunk):
    send(TARGET_IP, PORT, img.flatten()[chunk*PACKET_SIZE:(chunk+1)*PACKET_SIZE].tobytes(), sock)
    try:
        res_SEQ, res_BAT = wait_for_ack(sock)
        # print(f"ACK SEQ {int(res_SEQ)} BAT {int(res_BAT)}")
        return True
    except Exception:
        return False

def inf(img):
    for i in range(NUM_BAT):
        err_count = 0
        success = False
        while (not success and err_count < MAX_TRIES):
            success = send_img_chunk(img, i)
            err_count += 1

        if not success:
            print("TIMEOUT")
            sys.exit(0)

    res_data = receive(sock)

    if res_data:
        res_SEQ, res_CLASS = res_data[0], res_data[1]
        return int(res_CLASS)
    else:
        print("Accelerator did not respond")
        return -1

if __name__ == "__main__":
    if len(sys.argv) < 2:
        times = []
        corrects = []
        for i in range(10):
            t0 = time.time()
            res = inf(test_images[i])
            times.append(time.time() - t0)
            corrects.append(res == int(test_labels[i]))
            print(f"expected {CLASSES[int(test_labels[i])]}, got {CLASSES[res]}")


        print(f"accuracy: {sum(corrects) / len(corrects)}")
        print(f"average time: {sum(times)/len(times)}")
    else:
        img_idx = int(sys.argv[1])
        img = test_images[img_idx]
        label = int(test_labels[img_idx])
        res = inf(img)
        print(f"expected {CLASSES[label]}, got {CLASSES[res]}")