# standard libs
import logging
import pathlib
import argparse
import os
import time

logging.getLogger("tensorflow").setLevel(logging.DEBUG)

import tensorflow as tf
import numpy as np
import pandas as pd

MODEL_PATH = "../model_prep/models/model.pt"
NUM_ROUNDS = 1
NUM_IMGS = 10000

# load model
model = tf.keras.models.load_model(MODEL_PATH)
# load dataset
(_, _), (test_images, test_labels) = tf.keras.datasets.cifar10.load_data()

# define columns of output frame
columns = ["round", "img_id", "label", "prediction", "time"]
output_frame = pd.DataFrame(columns=columns, index=range(0, NUM_ROUNDS * NUM_IMGS))
# do 30 times
row_idx = 0
for round in range(NUM_ROUNDS):
    print(f"round {round}/{NUM_ROUNDS}")
    # iterate over the entire dataset
    for idx in range(NUM_IMGS): 
        # reformat input image to batch of size 1
        input_img = np.asarray([test_images[idx]])
        # start timer
        t0 = time.time()
        # run inference
        result = model(input_img)
        # stop timer
        t1 = time.time()
        # fill output
        output_frame.loc[row_idx, "round"] = round
        output_frame.loc[row_idx, "img_id"] = idx
        output_frame.loc[row_idx, "label"] = test_labels[idx][0]
        output_frame.loc[row_idx, "prediction"] = np.argmax(result)
        output_frame.loc[row_idx, "time"] = t1 - t0
        # increment row idx
        row_idx += 1

print(output_frame.head())
print(output_frame.tail())

output_frame.to_csv('results/cpu_benchmark.csv')