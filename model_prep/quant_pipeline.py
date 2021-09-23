"""
resources:
quantization:         https://www.tensorflow.org/lite/performance/post_training_quantization
parameter extraction: https://blog.xmartlabs.com/2019/11/22/TFlite-to-CoreML/

"""


# standard libs
import logging
import pathlib
import argparse

logging.getLogger("tensorflow").setLevel(logging.DEBUG)

# local
import models
import data_loader

# 3rd party
import tensorflow as tf
import numpy as np

# constants
MODEL_SAVE_PATH = "../models/mnist_model.pt"
QUANT_MODEL_SAVE_PATH = "../models/mnist_model_quant.tflite"


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
    args = vars(parser.parse_args())
    return args


"""
============================================================================================
Main Functions
============================================================================================
"""


def train(train_set, test_set, model):
    # unpack
    train_images, train_labels = train_set
    test_images, test_labels = test_set

    # Train the digit classification model
    model.compile(
        optimizer="adam",
        loss=tf.keras.losses.SparseCategoricalCrossentropy(from_logits=True),
        metrics=["accuracy"],
    )
    model.fit(
        train_images, train_labels, epochs=5, validation_data=(test_images, test_labels)
    )

    return model


def quantize(model):

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_data_gen
    # Ensure that if any ops can't be quantized, the converter throws an error
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    # Set the input and output tensors to uint8 (APIs added in r2.3)
    converter.inference_input_type = tf.uint8
    converter.inference_output_type = tf.uint8

    tflite_model_quant = converter.convert()

    interpreter = tf.lite.Interpreter(model_content=tflite_model_quant)
    input_type = interpreter.get_input_details()[0]["dtype"]
    print("input: ", input_type)
    output_type = interpreter.get_output_details()[0]["dtype"]
    print("output: ", output_type)

    # Save the quantized model:
    pathlib.Path(QUANT_MODEL_SAVE_PATH).write_bytes(tflite_model_quant)


def evaluate_model(model_type, test_set):
    test_images, test_labels = test_set

    test_image_indices = range(test_images.shape[0])
    predictions = run_tflite_model(QUANT_MODEL_SAVE_PATH, test_image_indices, test_set)

    accuracy = (np.sum(test_labels == predictions) * 100) / len(test_images)

    print(
        "%s model accuracy is %.4f%% (Number of test samples=%d)"
        % (model_type, accuracy, len(test_images))
    )


if __name__ == "__main__":
    args = parse_args()
    # load training data
    train_set, test_set = data_loader.load_mnist()

    if args["train"]:
        # load model architecture
        model = models.basic_conv_model
        model.summary()
        # train model
        train(train_set, test_set, model)
        save_model(model)
    else:
        model = load_model()

    quantize(model)
    evaluate_model("Quantized", test_set)
