import tensorflow as tf
import numpy as np
from tensorflow.python.keras.layers import Lambda

footer = "};"
network_path = "/home/bossebandowski/thesis/model_prep/models/mnist_model_quant.tflite"

interpreter = tf.lite.Interpreter(model_path=str(network_path))
interpreter.allocate_tensors()


input_details = interpreter.get_input_details()
output_details = interpreter.get_output_details()

print("== Input details ==")
print("name:", input_details[0]["name"])
print("shape:", input_details[0]["shape"])
print("type:", input_details[0]["dtype"])
print("\n== Output details ==")
print("name:", output_details[0]["name"])
print("shape:", output_details[0]["shape"])
print("type:", output_details[0]["dtype"])

tensor_details = interpreter.get_tensor_details()
names = []

for dict in tensor_details:
    i = dict["index"]
    tensor_name = dict["name"]
    scales = dict["quantization_parameters"]["scales"]
    zero_points = dict["quantization_parameters"]["zero_points"]
    tensor = interpreter.tensor(i)()
    names.append(tensor_name)

print("num tensors:", len(tensor_details))


def extract_weights(interpreter):
    params = []

    for i in range(2, int(len(tensor_details) / 2)):
        params.append(interpreter.get_tensor(i))

    return params


def save_conv_weights(fname, weights, padding=0):
    c_out, dim_x, dim_y, c_in = weights.shape

    if padding == 0:
        out = (
            "const int8_t "
            + fname
            + "["
            + str(c_out * dim_x * dim_y * c_in)
            + "] = {\n\t"
        )
        for a in range(c_out):
            for b in range(dim_x):
                for c in range(dim_y):
                    for d in range(c_in):
                        out += str(weights[a, b, c, d]) + ",\n\t"
        out = out[:-3] + "\n" + footer
    else:
        raise (ValueError("padding parameter must be 0"))

    with open("../tmp/" + fname + ".c", "w") as f:
        f.write(out)


def save_fc_weights(fname, weights):
    len_x, len_y = weights.shape
    out = "const int8_t " + fname + "[" + str(len_x * len_y) + "] = {\n\t"
    for x in range(len_x):
        for y in range(len_y):
            out += str(weights[x, y]) + ",\n\t"
    out = out[:-3] + "\n" + footer
    with open("../tmp/" + fname + ".c", "w") as f:
        f.write(out)


def save_biases(fname, biases):
    out = "const int32_t " + fname + "[" + str(len(biases)) + "] = {\n\t"

    for x in range(len(biases)):
        out += str(biases[x]) + ",\n\t"
    out = out[:-3] + "\n" + footer
    with open("../tmp/" + fname + ".c", "w") as f:
        f.write(out)


params = extract_weights(interpreter)

for i, layer in enumerate(params):
    print(i, layer.dtype, layer.shape)
    if len(layer.shape) == 4:
        save_conv_weights(f"{i}_w_conv", layer)
    elif len(layer.shape) == 2:
        save_fc_weights(f"{i}_w_fc", layer)
    elif len(layer.shape) == 1:
        save_biases(f"{i}_b", layer)
    else:
        raise (ValueError("Layer type not recognized. Check dim"))

mnist = tf.keras.datasets.mnist
(train_images, train_labels), (_, _) = mnist.load_data()


def save_example_img(idx):
    img = train_images[idx]
    label = train_labels[idx]
    len_x, len_y = img.shape
    out = "const int8_t " + "img_" + str(label) + "[" + str(len_x * len_y) + "] = {\n\t"
    for x in range(len_x):
        for y in range(len_y):
            out += str(img[x, y]) + ",\n\t"
    out = out[:-3] + "\n" + footer
    with open("../tmp/img_#" + str(idx) + "_expected_" + str(label) + ".txt", "w") as f:
        f.write(out)


for i in range(100):
    save_example_img(i)
