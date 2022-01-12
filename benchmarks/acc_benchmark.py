import socket
import tensorflow as tf
import numpy as np
import time
import sys
import cv2
import pandas as pd

NUM_ROUNDS = 1
NUM_IMGS = 10000

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

SEQ = 0
BAT = 0
NUM_BAT = 3
MAX_TRIES = 3
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

def main():
    # load dataset
    (_, _), (test_images, test_labels) = tf.keras.datasets.cifar10.load_data()

    # define columns of output frame
    columns = ["round", "img_id", "label", "prediction", "time_gross", "time_net"]
    output_frame = pd.DataFrame(columns=columns, index=range(0, NUM_ROUNDS * NUM_IMGS))

    row_idx = 0
    for round in range(NUM_ROUNDS):
        print(f"round {round + 1}/{NUM_ROUNDS}")
        # iterate over the entire dataset
        for idx in range(NUM_IMGS): 
            # reformat input image to batch of size 1
            input_img = np.asarray([test_images[idx]])
            # start timer
            t0 = time.time()
            # run inference
            prediction = inf(test_images[idx])
            # stop timer
            t1 = time.time()
            # fill output
            output_frame.loc[row_idx, "round"] = round
            output_frame.loc[row_idx, "img_id"] = idx
            output_frame.loc[row_idx, "label"] = test_labels[idx][0]
            output_frame.loc[row_idx, "prediction"] = prediction
            output_frame.loc[row_idx, "time_gross"] = t1 - t0
            # increment row idx
            row_idx += 1

    print(output_frame.head())
    print(output_frame.tail())

    output_frame.to_csv('results/cpu_benchmark.csv')



if __name__ == "__main__":
    main()
