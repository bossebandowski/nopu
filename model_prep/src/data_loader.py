import tensorflow as tf
# import tensorflow_datasets as tfds
import numpy as np
import pickle
import os

DATASETS = ["mnist", "cifar", "imgnet64"]
IMGNET_PATH="../datasets/imagenet64/train"

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
"""
def load_imgnet64():
    def load_databatch(data_folder, idx, img_size=64):
        data_file = os.path.join(data_folder, f'train_data_batch_{idx}.npz')
        print(data_file)
        print(os.path.isfile(data_file))
        print(data_file)
        batch = np.load(data_file)
        x = batch["data"].astype(np.float64)
        mean_image = batch["mean"].astype(np.float64)
        y = batch["labels"].astype(np.float64)

        x = x
        mean_image = mean_image

        # Labels are indexed from 1, shift it so that indexes start at 0
        y = [i-1 for i in y]
        data_size = x.shape[0]

        # subtract the mean
        x -= mean_image

        img_size2 = img_size * img_size
        x = np.dstack((x[:, :img_size2], x[:, img_size2:2*img_size2], x[:, 2*img_size2:]))
        x = x.reshape((x.shape[0], img_size, img_size, 3)).transpose(0, 3, 1, 2)

        # create mirrored images
        X_train = x[0:data_size, :, :, :]
        Y_train = y[0:data_size]
        X_train_flip = X_train[:, :, :, ::-1]
        Y_train_flip = Y_train
        X_train = np.concatenate((X_train, X_train_flip), axis=0)
        Y_train = np.concatenate((Y_train, Y_train_flip), axis=0)

        return dict(
            X_train=X_train,
            Y_train=Y_train.astype('int32'),
            mean=mean_image)

    ds = load_databatch(IMGNET_PATH, 1)
    Xs = ds['X_train']
    Ys = ds['Y_train']

    for i in range(2, 10):
        ds = load_databatch(IMGNET_PATH, i)
        Xs = np.concatenate((Xs, ds['X_train']), axis=0)
        Ys = np.concatenate((Ys, ds['Y_train']), axis=0)

    print(Xs.shape)
"""