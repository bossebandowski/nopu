import tensorflow as tf
import numpy as np
from tensorflow.python.keras.layers import Lambda

header = "#include <stdint.h>\n\n"
footer = "};\n"
network_path = "/home/bossebandowski/nopu/model_prep/models/8x32_model.tflite"
param_fname = "parameters"
image_fname = "images"
param_path = "../../model_parameters/"

def parse_conv_weights(weights, name, padding=0):
    c_out, dim_x, dim_y, c_in = weights.shape

    if padding == 0:
        out = (
            "const int8_t "
            + name
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
    len_x, len_y = img.shape
    out = "const int32_t " + "img_" + str(idx) + "[" + str(len_x * len_y) + "] = {\n\t"
    for x in range(len_x):
        for y in range(len_y):
            out += str(img[x, y]) + ",\n\t"
    out = out[:-3] + "\n" + footer
    return out, label

def extract_layer_parameters(layers):
    out = header

    for layer_id in layers.keys():
        print(layers[layer_id]["name"])
        if not ";" in layers[layer_id]["name"]:
            if "MatMul" in layers[layer_id]["name"]:
                out += parse_fc_weights(layers[layer_id]["tensor"], f"param_{layer_id}_w_fc")
            elif "BiasAdd" in layers[layer_id]["name"]:
                out += parse_biases(layers[layer_id]["tensor"], f"param_{layer_id}_b")
            elif "Conv2D" in layers[layer_id]["name"]:
                out += parse_conv_weights(layers[layer_id]["tensor"], f"param_{layer_id}_w_conv")

    return out

def save_example_images(count):
    mnist = tf.keras.datasets.mnist
    (_, _), (test_images, test_labels) = mnist.load_data()

    image_file_content = header
    result_arr = "const int results[" + str(count) + "] = {\n"
    collection_arr = "const int32_t* images[" + str(count) + "] = {\n"
    
    for i in range(count):
        content, label = parse_example_img(i, test_images, test_labels)
        image_file_content += content
        result_arr += str(label) + ", "
        collection_arr += "img_" + str(i) + ",\n"

    result_arr = result_arr[:-2] + footer
    collection_arr = collection_arr[:-2] + footer

    image_file_content += result_arr + collection_arr

    with open("../tmp/" + image_fname + ".h", "w") as f:
        f.write(image_file_content)

def main():
    interpreter = tf.lite.Interpreter(model_path=str(network_path))
    interpreter.allocate_tensors()

    tensor_details = interpreter.get_tensor_details()
    layers = {}

    for dict in tensor_details:
        layers[dict["index"]] = {"name": dict["name"], "tensor": interpreter.tensor(dict["index"])()}
        
    param_file_content = extract_layer_parameters(layers)
    with open(param_path + param_fname + ".h", "w") as f:
        f.write(param_file_content)

    # save_example_images(10)

if __name__ == "__main__":
    main()