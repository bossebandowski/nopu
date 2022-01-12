
import argparse
import time

import numpy as np
import pandas as pd
from pycoral.adapters import classify
from pycoral.adapters import common
from pycoral.utils.dataset import read_label_file
from pycoral.utils.edgetpu import make_interpreter

LABEL_PATH = 'test_data/labels.txt'
IMAGE_PATH = 'test_data/images.npy'
MODEL_PATH = 'model/8x32_model_qat.tflite'
NUM_IMGS = 10000
NUM_ROUNDS = 1

def main():
    labels = read_label_file(LABEL_PATH)
    images = np.load(IMAGE_PATH)
    interpreter = make_interpreter(MODEL_PATH)
    interpreter.allocate_tensors()

    # Model must be uint8 quantized
    if common.input_details(interpreter, 'dtype') != np.uint8:
        raise ValueError('Only support uint8 input type.')

    # define columns of output frame
    columns = ["round", "img_id", "label", "prediction", "time_gross", "time_net"]
    output_frame = pd.DataFrame(columns=columns, index=range(0, NUM_ROUNDS * NUM_IMGS))

    # warm up round
    common.set_input(interpreter, images[0])
    interpreter.invoke()

    row_idx = 0
    for round in range(NUM_ROUNDS):
        print(f"round {round + 1}/{NUM_ROUNDS}")
        # iterate over the entire dataset
        for i in range(NUM_IMGS):
            t0 = time.perf_counter()
            common.set_input(interpreter, images[i])
            t1 = time.perf_counter()
            interpreter.invoke()
            prediction = classify.get_classes(interpreter, 1, 0)[0].id
            t2 = time.perf_counter()
            # fill output
            output_frame.loc[row_idx, "round"] = round
            output_frame.loc[row_idx, "img_id"] = i
            output_frame.loc[row_idx, "label"] = labels[i]
            output_frame.loc[row_idx, "prediction"] = prediction
            output_frame.loc[row_idx, "time_gross"] = t2 - t0
            output_frame.loc[row_idx, "time_net"] = t2 - t1
            # increment row idx
            row_idx += 1

    print(output_frame.head())
    print(output_frame.tail())

    output_frame.to_csv('results/tpu_benchmark.csv')

if __name__ == '__main__':
    main()
