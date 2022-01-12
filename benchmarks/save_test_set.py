"""
The point of this small script is to remove tensorflow from the list of dependencies when running the
tpu benchmark as I had trouble getting both tensorflow and pycoral running on some versions of python.
"""

import tensorflow as tf
import numpy as np

LABEL_PATH = 'test_data/labels.txt'
IMAGE_PATH = 'test_data/images.npy'
W = 32
C = 3

def save_labels(test_labels):
    label_file = open(LABEL_PATH, 'w')
    for label_list in test_labels:
        label_file.writelines(str(label_list[0]) + '\n')
    label_file.close()

def save_images(test_images):
    data = np.zeros((len(test_images), W, W, C))
    for img_id in range(len(test_images)):
        data[img_id] = test_images[img_id]
        
    with open(IMAGE_PATH, 'wb') as image_file:
        np.save(image_file, data)

def main():
    (_, _), (test_images, test_labels) = tf.keras.datasets.cifar10.load_data()
    save_labels(test_labels)
    save_images(test_images)


if __name__ == "__main__":
    main()