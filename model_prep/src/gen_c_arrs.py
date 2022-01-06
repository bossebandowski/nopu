import tensorflow as tf
import numpy as np
from tensorflow.python.keras.layers import Lambda
import os

header = "#include <stdint.h>\n\n"
footer = "};\n"
network_path = "../models/8x32_model_qat.tflite"
param_path = "../../model_parameters/"
param_fname = "parameters"
image_path = "../../hardware_test/"
image_fname = "images"
N = 32
num_channels = 16

def parse_conv_weights(weights, name, padding=0):
    c_out, dim_x, dim_y, c_in = weights.shape

    if padding == 0:
        out = (
            "const int8_t "
            + name
            + "["
            + str(c_out * dim_x * (dim_y + 1) * c_in)
            + "] = {\n\t"
        )
        for a in range(c_out):
            for d in range(c_in):
                for b in range(dim_x):
                    for c in range(dim_y):
                            out += str(weights[a, b, c, d]) + ",\n\t"
                    out += "0,\n\t"     # add 0 to align odd filter size with burst size
                    
        out = out[:-3] + "\n" + footer
    else:
        raise (ValueError("padding parameter must be 0"))

    return out

def parse_fc_weights(weights, name):
    weights = np.transpose(weights, (1, 0))
    len_x, len_y = weights.shape
    out = "const int8_t " + name + "[" + str(len_x * len_y) + "] = {\n\t"
    for x in range(len_x):
        for y in range(len_y):
            out += str(weights[x, y]) + ",\n\t"
    out = out[:-3] + "\n" + footer

    return out

def parse_biases(biases, name):
    out = "const int32_t " + name + "[" + str(len(biases)) + "] = {\n\t"

    for x in range(len(biases)):
        out += str(biases[x]) + ",\n\t"
    out = out[:-3] + "\n" + footer

    return out

def parse_example_img(idx, images, labels):
    img = images[idx]
    label = labels[idx]
    if type(label) == np.ndarray:
        label = label[0]

    len_x, len_y, len_z = img.shape
    out = "const int32_t " + "img_" + str(idx) + "[" + str(len_x * len_y * len_z) + "] = {\n\t"
    for x in range(len_x):
        for y in range(len_y):
            for z in range(len_z):
                out += str(img[x, y, z]) + ",\n\t"
    out = out[:-3] + "\n" + footer
    return out, label

def parse_m(M, id):
    out = "const int32_t ms_" + str(id) + "[" + str(num_channels) + "] = {\n\t"
    for channel in range(len(M)):
        M0 = int(M[channel]/2**(-N))
        out += str(M0) + ",\n\t"
    out = out[:-3] + "\n" + footer
    return out

def extract_layer_parameters(layers):
    out = header

    for layer_id in layers.keys():
        print(layers[layer_id]["name"])
        if not ";" in layers[layer_id]["name"]:
            if "MatMul" in layers[layer_id]["name"]:
                out += parse_fc_weights(layers[layer_id]["tensor"], f"param_{layer_id}_w_fc")
            elif "/bias" in layers[layer_id]["name"]:
                out += parse_biases(layers[layer_id]["tensor"], f"param_{layer_id}_b")
            elif "Conv2D" in layers[layer_id]["name"]:
                out += parse_conv_weights(layers[layer_id]["tensor"], f"param_{layer_id}_w_conv")

    return out

def extract_real_layer_id(name):
    if len(name.split("/")) == 2:
        return int(name.split("/")[0].split("_")[-1])
    else:
        return int(name.split("/")[1].split("_")[-1])

def extract_layer_parameters_qat(layers):
    biases = {}
    fcs = {}
    convs = {}
    activations = {}
    out = header

    for layer_id in layers.keys():
        name = layers[layer_id]["name"]
        if "/MatMul" in name and not "/BiasAdd" in name:
            fcs[extract_real_layer_id(name)] = layers[layer_id]["tensor"]
        elif ("/bias" in name and not "quant" in name) or ("/BiasAdd" in name and not ";" in name):
            biases[extract_real_layer_id(name)] = (layers[layer_id]["tensor"], layers[layer_id]["qp"]["scales"])
        elif "/Conv2D" in name and not "/BiasAdd" in name:
            convs[extract_real_layer_id(name)] = layers[layer_id]["tensor"]
        elif "/Relu;" in name:
            activations[extract_real_layer_id(name)] = layers[layer_id]["qp"]["scales"]

    for key in sorted(convs):
        out += parse_conv_weights(convs[key], f"param_{key}_w_conv")

    for key in sorted(fcs):
        out += parse_fc_weights(fcs[key], f"param_{key}_w_fc")

    for key in sorted(biases):
        tensor, _ = biases[key]
        out += parse_biases(tensor, f"param_{key}_b")

    for key in sorted(activations):
        _, bias_scale = biases[key]
        activation_scale = activations[key]
        out += parse_m(bias_scale / activation_scale, key)

    return out

def save_example_images(count):
    cifar = tf.keras.datasets.cifar10
    (_, _), (test_images, test_labels) = cifar.load_data()

    image_file_content = header
    result_arr = "const int results[" + str(count) + "] = {\n\t"
    collection_arr = "const int32_t* images[" + str(count) + "] = {\n\t"
    
    for i in range(count):
        content, label = parse_example_img(i, test_images, test_labels)
        image_file_content += content
        result_arr += str(label) + ", "
        collection_arr += "img_" + str(i) + ",\n\t"

    result_arr = result_arr[:-3] + "\n" + footer
    collection_arr = collection_arr[:-3] + "\n" + footer

    image_file_content += result_arr + collection_arr

    with open(os.path.join(image_path, image_fname + ".h"), "w") as f:
        f.write(image_file_content)

def main():
    interpreter = tf.lite.Interpreter(model_path=str(network_path))
    interpreter.allocate_tensors()

    tensor_details = interpreter.get_tensor_details()
    layers = {}

    for dict in tensor_details:
        layers[dict["index"]] = {"name": dict["name"], "tensor": interpreter.tensor(dict["index"])(), "qp": dict["quantization_parameters"]}
        
    param_file_content = extract_layer_parameters_qat(layers)
    with open(param_path + param_fname + ".h", "w") as f:
        f.write(param_file_content)

    save_example_images(10)

if __name__ == "__main__":
    main()