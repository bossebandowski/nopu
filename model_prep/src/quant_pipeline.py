"""
resources:
quantization:         https://www.tensorflow.org/lite/performance/post_training_quantization
parameter extraction: https://blog.xmartlabs.com/2019/11/22/TFlite-to-CoreML/

"""


# standard libs
import logging
import pathlib
import argparse
import os

logging.getLogger("tensorflow").setLevel(logging.DEBUG)

# local
import models
import data_loader

# 3rd party
import tensorflow as tf
import tensorflow_model_optimization as tfmot
import numpy as np

# constants
MODEL_SAVE_PATH = "../models/model.pt"
QUANT_MODEL_SAVE_PATH = "../models/"
MODELS = models.DESCRIPTOR_LIST
DATASETS = data_loader.DATASETS

"""
============================================================================================
Quantization Helper Functions
============================================================================================
"""


def representative_data_gen():
    (train_images, _), (_, _) = data_loader.load_mnist()
    for input_value in (
        tf.data.Dataset.from_tensor_slices(train_images).batch(1).take(100)
    ):
        yield [input_value]


# Helper function to run inference on a TFLite model
def run_tflite_model(tflite_file, test_image_indices, test_set):
    test_images, test_labels = test_set

    # Initialize the interpreter
    interpreter = tf.lite.Interpreter(model_path=str(tflite_file))
    interpreter.allocate_tensors()

    input_details = interpreter.get_input_details()[0]
    output_details = interpreter.get_output_details()[0]

    predictions = np.zeros((len(test_image_indices),), dtype=int)
    for i, test_image_index in enumerate(test_image_indices):
        test_image = test_images[test_image_index]
        test_label = test_labels[test_image_index]

        # Check if the input type is quantized, then rescale input data to uint8
        if input_details["dtype"] == np.uint8:
            input_scale, input_zero_point = input_details["quantization"]
            test_image = test_image / input_scale + input_zero_point

        test_image = np.expand_dims(test_image, axis=0).astype(input_details["dtype"])
        interpreter.set_tensor(input_details["index"], test_image)
        interpreter.invoke()
        output = interpreter.get_tensor(output_details["index"])[0]

        predictions[i] = output.argmax()

    return predictions


def save_model(model):
    tf.keras.models.save_model(model, MODEL_SAVE_PATH)


def load_model():
    return tf.keras.models.load_model(MODEL_SAVE_PATH)


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--train", action="store_true", help="train a new model")
    parser.add_argument(
        "-m",
        "--model",
        type=str,
        default="basic_conv",
        help="specify which model to train. Choose between " + str(MODELS),
    )
    parser.add_argument(
        "-ds",
        "--dataset",
        type=str,
        default="mnist",
        help="specify which model to train. 'mnist' or 'cifar'. Make sure it matches the model input layer",
    )
    parser.add_argument("--qat", action="store_true", help="retrain qat model. Requires --train flag")
    parser.add_argument("--ptq", action="store_true", help="apply post training quantization (both 32 and 16 bit activations)")
    args = vars(parser.parse_args())
    return args


"""
============================================================================================
Main Functions
============================================================================================
"""


def train(train_set, model):
    # unpack
    train_images, train_labels = train_set

    # Train the digit classification model
    model.compile(
        optimizer="adam",
        loss=tf.keras.losses.SparseCategoricalCrossentropy(from_logits=True),
        metrics=["accuracy"],
    )
    model.fit(
        train_images, train_labels, epochs=5, validation_split=0.1
    )

    return model


def qat_8x32(train_set, model, path):
    
    """
    code take from
    https://www.tensorflow.org/model_optimization/guide/quantization/training_example
    """


    train_images, train_labels = train_set

    quantize_model = tfmot.quantization.keras.quantize_model
    # q_aware stands for for quantization aware.
    q_aware_model = quantize_model(model)

    # `quantize_model` requires a recompile.
    q_aware_model.compile(optimizer='adam',
                loss=tf.keras.losses.SparseCategoricalCrossentropy(from_logits=True),
                metrics=['accuracy'])
    q_aware_model.summary()
    # run qat
    q_aware_model.fit(train_images, train_labels, epochs=5, validation_split=0.1)

    return q_aware_model

def quantize_8x32_qat(model, path):
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]

    tflite_model_quant = converter.convert()
    
    # Save the quantized model:
    pathlib.Path(path).write_bytes(tflite_model_quant)


def quantize_8x32(model, path):

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_data_gen
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]

    # Set the input and output tensors to uint8 (APIs added in r2.3)
    converter.inference_input_type = tf.uint8
    converter.inference_output_type = tf.uint8

    tflite_model_quant = converter.convert()

    # Save the quantized model:
    pathlib.Path(path).write_bytes(tflite_model_quant)


def quantize_8x16(model, path):
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_data_gen
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.EXPERIMENTAL_TFLITE_BUILTINS_ACTIVATIONS_INT16_WEIGHTS_INT8
    ]

    tflite_model_quant = converter.convert()

    # Save the quantized model:
    pathlib.Path(path).write_bytes(tflite_model_quant)


def evaluate_model(model_type, test_set, path):
    test_images, test_labels = test_set

    test_image_indices = range(test_images.shape[0])
    predictions = run_tflite_model(path, test_image_indices, test_set)


    if len(test_labels.shape) > 1:
        test_labels = test_labels.flatten()

    accuracy = (np.sum(test_labels == predictions) * 100) / len(test_images)

    print(
        "%s model accuracy is %.4f%% (Number of test samples=%d)"
        % (model_type, accuracy, len(test_images))
    )

def load_data(dataset, dl):
    if dataset == "mnist":
        (train_images, train_labels), (test_images, test_labels) = dl.load_mnist()
    elif dataset == "cifar":
        (train_images, train_labels), (test_images, test_labels) = dl.load_cifar10()
    elif dataset == "imgnet64":
        (train_images, train_labels), (test_images, test_labels) = dl.load_imgnet64()

    train_images, test_images = train_images / 255.0, test_images / 255.0
    
    return (train_images, train_labels), (test_images, test_labels)


if __name__ == "__main__":
    args = parse_args()
    assert(args["dataset"] in DATASETS)
    # load training data
    train_set, test_set = load_data(args["dataset"], data_loader)
    test_images, test_labels = test_set

    if args["train"]:
        descriptor = args["model"]
        # load model architecture
        model = models.get_model(descriptor)
        model.summary()
        # train model
        train(train_set, model)
        save_model(model)

        if args["qat"]:
            path32_qat = os.path.join(QUANT_MODEL_SAVE_PATH, "8x32_model_qat.tflite")
            qat_model = qat_8x32(train_set, model, path32_qat)
            quantize_8x32_qat(qat_model, path32_qat)
            evaluate_model("q8x32_qat", test_set, path32_qat)

    else:
        model = load_model()
        model.summary()
        evaluate_model("saved qat model", test_set, os.path.join(QUANT_MODEL_SAVE_PATH, "8x32_model_qat.tflite"))


    if args["ptq"]:
        path32 = os.path.join(QUANT_MODEL_SAVE_PATH, "8x32_model.tflite")
        path16 = os.path.join(QUANT_MODEL_SAVE_PATH, "8x16_model.tflite")

        quantize_8x32(model, path32)
        print("=============================================")
        print("=============================================")

        evaluate_model("q8x32", test_set, path32)

        print("=============================================")
        print("=============================================")

        quantize_8x16(model, path16)

        print("=============================================")
        print("=============================================")

        evaluate_model("q8x16", test_set, path16)

        print("=============================================")
        print("=============================================")
