# standard libs
import argparse
import enum
import sys

# local
import models

# 3rd party
import tensorflow as tf
import numpy as np

# constants
MODELS = models.DESCRIPTOR_LIST
DTYPE = np.int32

def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "-l",
        "--layer",
        type=int,
        default=-1,
        help="specify which layer to print. Valid inputs depend on the model architecture",
    )
    parser.add_argument(
        "-o",
        "--offset",
        type=int,
        default=0,
        help="Used to specified which nodes in a given layer to print if they are of particular interest",
    )
    parser.add_argument(
        "-i",
        "--image",
        type=int,
        default=0,
        help="specify which image to use as input. Must be integer in [0, 4999]",
    )
    parser.add_argument(
        "-m",
        "--model",
        type=str,
        default="../models/8x32_model.tflite",
        help="specify which model to simulate. Full path (absolute or relative)",
    )
    parser.add_argument(
        "-t",
        "--type",
        type=str,
        default="min_pool",
        help="specify the model architecture. One of " + str(MODELS)
    )
    parser.add_argument("--top10", action="store_true", help="calculate the first then images")
    args = vars(parser.parse_args())
    return args

def get_layer_ids(layers):
    fcs = []
    bas = []
    convs = []
    for layer_id in layers.keys():
        if not ";" in layers[layer_id]["name"]:
            if "MatMul" in layers[layer_id]["name"]:
                fcs.append(layer_id)
            elif "/bias" in layers[layer_id]["name"]:
                bas.append(layer_id)
            elif "Conv2D" in layers[layer_id]["name"]:
                convs.append(layer_id)

    return fcs, bas, convs

def init_nodes(bas, layers):
    nodes = []
    for ba in bas:
        nodes.append(np.zeros(layers[ba]["tensor"].size, dtype=DTYPE))

    return nodes

def load_input(id):
    mnist = tf.keras.datasets.mnist
    (_, _), (test_images, test_labels) = mnist.load_data()
    return test_images[id].reshape(784), test_labels[id]

def load_weights(fcs, layers):
    weights = []
    for fc in fcs:
        weights.append(np.transpose(layers[fc]["tensor"], (1, 0)))
    return weights

def load_biases(bas, layers):
    biases = []
    for ba in bas:
        biases.append(layers[ba]["tensor"])
    return biases

def load_filters(convs, layers):
    filters = []
    for conv in convs:
        filters.append(layers[conv]["tensor"])

    return filters

def mac_fc(input, output, weights, mac_id):
    for in_id in range(len(input)):
        for out_id in range(len(output)):
            in_param = input[in_id]
            weight = weights[mac_id][in_id, out_id]
            output[out_id] = output[out_id] + in_param * weight

def apply_filter(ins, filter):
    return np.multiply(ins, filter.reshape((3, 3))).sum()

def extract_image_part(image, y, x):
    w = 28
    out = np.zeros((3, 3), dtype=DTYPE)
    out[0, 0] = image[(y - 1) * w + x - 1]
    out[0, 1] = image[(y - 1) * w + x]
    out[0, 2] = image[(y - 1) * w + x + 1]
    out[1, 0] = image[y * w + x - 1]
    out[1, 1] = image[y * w + x]
    out[1, 2] = image[y * w + x + 1]
    out[2, 0] = image[(y + 1) * w + x - 1]
    out[2, 1] = image[(y + 1) * w + x]
    out[2, 2] = image[(y + 1) * w + x + 1]

    return out

def conv(input_layer, output, filters, layer, input_shape):
    # input dimensions of image (28x28x1 for MNIST)
    in_x, in_y = input_shape
    # output dimensions of feature maps after convolution assuming 0-padding (26x26)
    out_x, out_y = in_x - 2, in_y - 2
    # the number of convolutions (16 for minimal conv)
    c_out, _, _, _ = filters[0].shape
    # temporary clone of output layer before flattening, mirroring tf info (26x26x16)
    outputs = np.zeros((out_x, out_y, c_out), dtype=DTYPE)
    # for every kernel, produce an output mask
    for a in range(c_out):
        # init output mask
        out_mask = np.zeros((out_x, out_y), dtype=DTYPE)
        # load corresponding filter
        filter = filters[0][a]
        # iterate over input image (and ignore edges) and map to output masks
        for x in range(1, in_x - 1):
            for y in range(1, in_y - 1):
                ins = extract_image_part(input_layer, x, y)
                out_mask[x - 1, y - 1] = apply_filter(ins, filter)

        outputs[:, :, a] = out_mask


    output[layer] = outputs

def calc_flat_address(x, y, z, mx, my, mz):
    return x * mz + y * mz * mx + z

def flatten(nodes, layer):
    x, y, z = nodes[layer].shape
    nodes[layer] = nodes[layer].reshape(x * y * z)

def max_pool(nodes, in_shape, out_shape, p_shape, layer):
    inx, iny, inz = in_shape
    outx, outy, outz = out_shape
    px, py = p_shape
    pool_output = np.zeros(out_shape)
    layer_in = nodes[layer - 1]
    stride = 2

    for z in range(inz):
        for y in range(int(iny/stride)):
            for x in range(int(inx/stride)):
                max_val = int(-sys.maxsize - 1)
                for fx in range(px):
                    for fy in range(py):
                        max_val = max(max_val, layer_in[stride * x + fx, stride * y + fy, z])
                pool_output[x, y, z] = max_val

    nodes.insert(layer, pool_output)

def bias_relu_conv(outputs, biases):
    x, y, z = outputs.shape
    for i in range(x):
        for j in range(y):
            for k in range(z):
                outputs[i, j, k] = max(outputs[i, j, k] + biases[k], 0)

def bias_relu(outputs, biases, layer):
    for id in range(len(outputs[layer])):
        outputs[layer][id] = max(outputs[layer][id] + biases[layer][id], 0)

def bias(outputs, biases):
    for id in range(len(outputs)):
        outputs[id] = outputs[id] + biases[id]

def process_model_basic_fc(nodes, img, weights, filters, biases):
    # layer 0
    mac_fc(img, nodes[0], weights, 0)
    bias_relu(nodes, biases, 0)
    
    # last layer
    mac_fc(nodes[0], nodes[1], weights, 1)
    bias(nodes[1], biases[1])

    return np.argmax(nodes[-1])

def process_model_three_fc(nodes, img, weights, filters, biases):
    # layer 0
    mac_fc(img, nodes[0], weights, 0)
    bias_relu(nodes, biases, 0)
    
    # intermediate layers
    mac_fc(nodes[0], nodes[1], weights, 1)
    bias_relu(nodes, biases, 1)
    
    # last layer
    mac_fc(nodes[1], nodes[2], weights, 2)
    bias(nodes[2], biases[2])

    return np.argmax(nodes[-1])

def process_model_conv_minimal(nodes, img, weights, filters, biases):
    # layer 0
    conv(img, nodes, filters, 0, (28, 28))
    bias_relu_conv(nodes[0], biases[0])

    # flatten
    flatten(nodes, 0)

    # layer 1
    mac_fc(nodes[0], nodes[1], weights, 0)
    bias(nodes[1], biases[1])

    return np.argmax(nodes[-1])

def process_model_min_pool(nodes, img, weights, filters, biases):
    # layer 0
    conv(img, nodes, filters, 0, (28, 28))
    bias_relu_conv(nodes[0], biases[0])

    # layer 1: max-pool (2x2)
    max_pool(nodes, (26, 26, 16), (13, 13, 16), (2, 2), 1)

    # flatten
    flatten(nodes, 1)

    # layer 2
    mac_fc(nodes[1], nodes[2], weights, 0)
    bias(nodes[2], biases[1])

    return np.argmax(nodes[-1])

def print_nodes(nodes, layer, num_nodes, offset):
    for i in range(min(len(nodes[layer]), num_nodes)):
        print(offset + i, int(nodes[layer][offset + i]))


if __name__ == "__main__":
    args = parse_args()

    interpreter = tf.lite.Interpreter(model_path=args["model"])
    interpreter.allocate_tensors()

    tensor_details = interpreter.get_tensor_details()
    layers = {}

    for dict in tensor_details:
        layers[dict["index"]] = {"name": dict["name"], "tensor": interpreter.tensor(dict["index"])()}

    fcs, bas, convs = get_layer_ids(layers)
    nodes = init_nodes(bas, layers)
    img, label = load_input(args["image"])
    weights = load_weights(fcs, layers)
    biases = load_biases(bas, layers)
    filters = load_filters(convs, layers)

    if args["type"] == "basic_fc":
        process = process_model_basic_fc
    elif args["type"] == "three_fc":
        process = process_model_three_fc
    elif args["type"] == "basic_conv":
        print("basic conv not implemented yet")
        sys.exit(0)
    elif args["type"] == "min_conv":
        process = process_model_conv_minimal
    elif args["type"] == "min_pool":
        process = process_model_min_pool
    else:
        print("unknown model architecture descriptor. Exiting...")
        sys.exit(0)


    res = process(nodes, img, weights, filters, biases)
    print("========= inference result of image " + str(args["image"]) + " =========")
    print(f"EXPECTED {label}, RETURNED {res}")

    if args["layer"] >= 0:
        print("========= intermediate nodes in layer " + str(args["layer"]) + " =========")
        print_nodes(nodes, args["layer"], 100, args["offset"])

    if args["top10"]:
        print("========= top10 ============")
        count = 0
        num = 10

        for i in range(num):
            inp, label = load_input(i)
            nodes = init_nodes(bas, layers)
            res = process(nodes, inp, weights, filters, biases)

            print(f"EXPECTED {label}, RETURNED {res}")
            if res == label:
                count += 1

        print(f"{100 * count / num}% correct.")
