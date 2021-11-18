import tensorflow as tf
import numpy as np

DATASETS = ["mnist", "cifar"]


def load_mnist():

    # Load MNIST dataset
    mnist = tf.keras.datasets.mnist
    (train_images, train_labels), (test_images, test_labels) = mnist.load_data()

    train_images = train_images[..., np.newaxis]
    test_images = test_images[..., np.newaxis]

    return (train_images, train_labels), (test_images, test_labels)

def load_cifar10():
    cifar10 = tf.keras.datasets.cifar10
    (train_images, train_labels), (test_images, test_labels) = cifar10.load_data()
    
    return (train_images, train_labels), (test_images, test_labels)