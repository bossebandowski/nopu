import tensorflow as tf
import numpy as np


def load_mnist():

    # Load MNIST dataset
    mnist = tf.keras.datasets.mnist
    (train_images, train_labels), (test_images, test_labels) = mnist.load_data()

    # Normalize the input image so that each pixel value is between 0 to 1.
    train_images = train_images.astype(np.float32) / 255.0
    test_images = test_images.astype(np.float32) / 255.0

    train_images = train_images[..., np.newaxis]
    test_images = test_images[..., np.newaxis]

    return (train_images, train_labels), (test_images, test_labels)
