# standard libs
import argparse
import enum
import sys

# local
import models

# 3rd party
import tensorflow as tf
import numpy as np
import data_loader

# constants
MODELS = models.DESCRIPTOR_LIST
DTYPE = np.float64
DTYPE_WEIGHTS = np.int8

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
        "-n",
        "--num_nodes",
        type=int,
        default=100,
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
        default="../models/8x32_model_qat.tflite",
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
        name = layers[layer_id]["name"]
        if "/MatMul" in name and not "/BiasAdd" in name:
            fcs.append(layer_id)
        elif ("/bias" in name and not "quant" in name) or ("/BiasAdd" in name and not ";" in name):
            bas.append(layer_id)
        elif "/Conv2D" in name and not "/BiasAdd" in name:
            convs.append(layer_id)

    return fcs, bas, convs

def find_ms(layers):
    bias_s = []
    activation_s = []
    Ms = []

    for layer_id in layers.keys():
        name = layers[layer_id]["name"]
        if ("/bias" in name and not "quant" in name) or ("/BiasAdd" in name and not ";" in name):
            bias_s.append(layers[layer_id]["qp"]["scales"])
        elif "/Relu" in name:
            activation_s.append(layers[layer_id]["qp"]["scales"])

    for i in range(len(bias_s) - 1):
        Ms.append(bias_s[i] / activation_s[i])

    return Ms

def init_nodes(bas, layers):
    nodes = []
    for ba in bas:
        nodes.append(np.zeros(layers[ba]["tensor"].size, dtype=DTYPE))

    return nodes

def load_input(id, dataset):
    if dataset == "mnist":
        (_, _), (test_images, test_labels) = data_loader.load_mnist()
    elif dataset == "cifar":
        (_, _), (test_images, test_labels) = data_loader.load_cifar10()

    return test_images[id], test_labels[id]

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
            in_param = input[in_id].astype(DTYPE)
            weight = weights[mac_id][in_id, out_id].astype(DTYPE)
            output[out_id] = output[out_id] + in_param * weight

def apply_filter(ins, filter):
    return np.multiply(ins, filter).sum()

def extract_input(in_volume, filter_size, x, y):
    if filter_size == 3:
        return in_volume[x - 1 : x + 2, y - 1 : y + 2, :]
    elif filter_size == 5:
        return in_volume[x - 2 : x + 3, y - 2 : y + 3, :]
    else:
        sys.exit("FILTER SIZE NOT IMPLEMENTED")

def conv(input_layer, output, filters, layer, input_shape, filter_id):
    # input dimensions of image (28x28x1 for MNIST)
    in_x, in_y, in_z = input_shape
    c_out, f_x, f_y, c_in = filters[filter_id].shape # in_z and c_in are the same number
    out_x, out_y = in_x - f_x + 1, in_y - f_y + 1
    outputs = np.zeros((out_x, out_y, c_out), dtype=DTYPE)
    # for every kernel, produce an output mask
    for a in range(c_out):
        # init output mask
        out_mask = np.zeros((out_x, out_y), dtype=DTYPE)
        # load corresponding filter
        filter = filters[filter_id][a]

        # iterate over input image (and ignore edges) and map to output masks
        for x in range(1, in_x - 1):
            for y in range(1, in_y - 1):
                ins = extract_input(input_layer, f_x, x, y)
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
    pool_output = np.zeros(out_shape).astype(np.uint8)
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

def bias_conv(outputs, biases):
    x, y, z = outputs.shape
    for i in range(x):
        for j in range(y):
            for k in range(z):
                outputs[i, j, k] = outputs[i, j, k] + biases[k]

def relu(outputs, conv):
    if conv:
        x, y, z = outputs.shape
        for i in range(x):
            for j in range(y):
                for k in range(z):
                    outputs[i, j, k] = min(max(outputs[i, j, k], 0), 255)
    else:
        for id in range(len(outputs)):
            outputs[id] = min(max(outputs[id], 0), 255)

def type_cast(outputs, layer):
    outputs[layer] = outputs[layer].astype(np.uint8)

def bias(outputs, biases):
    for id in range(len(outputs)):
        outputs[id] = outputs[id] + biases[id]

def requantize_activations(outputs, layer, M, conv):
    if conv:
        output_q = np.zeros(outputs[layer].shape)
        for channel in range(len(M)):
            output_q[:, :, channel] = outputs[layer][:, :, channel] * M[channel]
        
        outputs[layer] = output_q
    else:
        output_q = outputs[layer] * M
        outputs[layer] = output_q

def process_model_basic_fc(nodes, img, weights, filters, biases, Ms):
    # a little hack to ensure backwards compatibility (first layer FC)
    image_as_list = [img]
    flatten(image_as_list, 0)
    img = image_as_list[0]

    # layer 0
    mac_fc(img, nodes[0], weights, 0)
    bias(nodes[0], biases[0])
    requantize_activations(nodes, 0, Ms[0], False)
    relu(nodes[0], False)
    type_cast(nodes, 0)
    
    # last layer
    mac_fc(nodes[0], nodes[1], weights, 1)
    bias(nodes[1], biases[1])

    return np.argmax(nodes[-1])

def process_model_three_fc(nodes, img, weights, filters, biases, Ms):
    # a little hack to ensure backwards compatibility (first layer FC)
    image_as_list = [img]
    flatten(image_as_list, 0)
    img = image_as_list[0]

    # layer 0
    mac_fc(img, nodes[0], weights, 0)
    bias(nodes[0], biases[0])
    requantize_activations(nodes, 0, Ms[0], False)
    relu(nodes[0], False)
    type_cast(nodes, 0)

    # intermediate layer
    mac_fc(nodes[0], nodes[1], weights, 1)
    bias(nodes[1], biases[1])
    requantize_activations(nodes, 1, Ms[1], False)
    relu(nodes[1], False)
    type_cast(nodes, 1)
    
    # last layer
    mac_fc(nodes[1], nodes[2], weights, 2)
    bias(nodes[2], biases[2])

    return np.argmax(nodes[-1])

def process_model_conv_minimal(nodes, img, weights, filters, biases, Ms):
    # layer 0
    conv(img, nodes, filters, 0, (28, 28, 1), 0)
    bias_conv(nodes[0], biases[0])
    requantize_activations(nodes, 0, Ms[0], True)
    relu(nodes[0], True)
    type_cast(nodes, 0)

    # flatten
    flatten(nodes, 0)

    # layer 1
    mac_fc(nodes[0], nodes[1], weights, 0)
    bias(nodes[1], biases[1])

    return np.argmax(nodes[-1])

def process_model_min_pool(nodes, img, weights, filters, biases, Ms):
    # layer 0
    conv(img, nodes, filters, 0, (28, 28, 1), 0)
    bias_conv(nodes[0], biases[0])
    requantize_activations(nodes, 0, Ms[0], True)
    relu(nodes[0], True)
    type_cast(nodes, 0)

    # layer 1: max-pool (2x2)
    max_pool(nodes, (26, 26, 16), (13, 13, 16), (2, 2), 1)

    # flatten
    flatten(nodes, 1)

    # layer 3
    mac_fc(nodes[1], nodes[2], weights, 0)
    bias(nodes[2], biases[1])

    return np.argmax(nodes[-1])

def process_model_basic_conv(nodes, img, weights, filters, biases, Ms):
    # layer 0
    conv(img, nodes, filters, 0, (28, 28, 1), 0)
    bias_conv(nodes[0], biases[0])
    requantize_activations(nodes, 0, Ms[0], True)
    relu(nodes[0], True)
    type_cast(nodes, 0)

    # layer 1: max-pool (2x2)
    max_pool(nodes, (26, 26, 16), (13, 13, 16), (2, 2), 1)

    # layer 2
    conv(nodes[1], nodes, filters, 2, (13, 13, 16), 1)
    bias_conv(nodes[2], biases[1])
    requantize_activations(nodes, 2, Ms[1], True)
    relu(nodes[2], True)
    type_cast(nodes, 2)

    # layer 3: max-pool (2x2)
    max_pool(nodes, (11, 11, 16), (5, 5, 16), (2, 2), 3)

    # flatten
    flatten(nodes, 3)

    # layer 4
    mac_fc(nodes[3], nodes[4], weights, 0)
    bias(nodes[4], biases[2])
    requantize_activations(nodes, 4, Ms[2], False)
    relu(nodes[4], False)
    type_cast(nodes, 4)

    # layer 5
    mac_fc(nodes[4], nodes[5], weights, 1)
    bias(nodes[5], biases[3])

    return np.argmax(nodes[-1])

def process_model_cifar(nodes, img, weights, filters, biases, Ms):
    # layer 0
    conv(img, nodes, filters, 0, (32, 32, 3), 0)
    bias_conv(nodes[0], biases[0])
    requantize_activations(nodes, 0, Ms[0], True)
    relu(nodes[0], True)
    type_cast(nodes, 0)

    # layer 1: max-pool (2x2)
    max_pool(nodes, (30, 30, 16), (15, 15, 16), (2, 2), 1)

    # layer 2
    conv(nodes[1], nodes, filters, 2, (15, 15, 16), 1)
    bias_conv(nodes[2], biases[1])
    requantize_activations(nodes, 2, Ms[1], True)
    relu(nodes[2], True)
    type_cast(nodes, 2)

    # layer 3: max-pool (2x2)
    max_pool(nodes, (13, 13, 16), (6, 6, 16), (2, 2), 3)

    # flatten
    flatten(nodes, 3)

    # layer 4
    mac_fc(nodes[3], nodes[4], weights, 0)    
    bias(nodes[4], biases[2])
    requantize_activations(nodes, 4, Ms[2], False)
    relu(nodes[4], False)
    type_cast(nodes, 4)

    # layer 5
    mac_fc(nodes[4], nodes[5], weights, 1)
    bias(nodes[5], biases[3])

    return np.argmax(nodes[-1])

def process_model_cifar_advanced(nodes, img, weights, filters, biases, Ms):
    # layer 0: conv 3x3
    conv(img, nodes, filters, 0, (32, 32, 3), 0)
    bias_conv(nodes[0], biases[0])
    requantize_activations(nodes, 0, Ms[0], True)
    relu(nodes[0], True)
    type_cast(nodes, 0)

    # layer 1: max-pool (2x2)
    max_pool(nodes, (30, 30, 32), (15, 15, 32), (2, 2), 1)

    # layer 2: conv 3x3
    conv(nodes[1], nodes, filters, 2, (15, 15, 32), 1)
    bias_conv(nodes[2], biases[1])
    requantize_activations(nodes, 2, Ms[1], True)
    relu(nodes[2], True)
    type_cast(nodes, 2)

    # layer 3: max-pool (2x2)
    max_pool(nodes, (13, 13, 64), (6, 6, 64), (2, 2), 3)

    # layer 4: conv 3x3
    conv(nodes[3], nodes, filters, 4, (4, 4, 64), 2)
    bias_conv(nodes[4], biases[2])
    requantize_activations(nodes, 4, Ms[2], True)
    relu(nodes[4], True)
    type_cast(nodes, 4)

    # flatten
    flatten(nodes, 4)

    # layer 5: fc
    mac_fc(nodes[4], nodes[5], weights, 0)    
    bias(nodes[5], biases[3])
    requantize_activations(nodes, 5, Ms[3], False)
    relu(nodes[5], False)
    type_cast(nodes, 5)

    # layer 6: fc
    mac_fc(nodes[5], nodes[6], weights, 1)
    bias(nodes[6], biases[4])

    return np.argmax(nodes[-1])

def print_nodes(nodes, layer, num_nodes, offset):
    if len(nodes[layer].shape) > 1:
        flatten(nodes, layer)

    for i in range(min(len(nodes[layer]), num_nodes)):
        print(offset + i, int(nodes[layer][offset + i]))


if __name__ == "__main__":
    args = parse_args()

    interpreter = tf.lite.Interpreter(model_path=args["model"])
    interpreter.allocate_tensors()

    tensor_details = interpreter.get_tensor_details()
    layers = {}

    for dict in tensor_details:
        layers[dict["index"]] = {"name": dict["name"], "tensor": interpreter.tensor(dict["index"])(), "qp": dict["quantization_parameters"]}

    fcs, bas, convs = get_layer_ids(layers)
    Ms = find_ms(layers)
    nodes = init_nodes(bas, layers)
    img, label = load_input(args["image"], "cifar")
    weights = load_weights(fcs, layers)
    biases = load_biases(bas, layers)
    filters = load_filters(convs, layers)

    if args["type"] == "basic_fc":
        process = process_model_basic_fc
    elif args["type"] == "three_fc":
        process = process_model_three_fc
    elif args["type"] == "basic_conv":
        process = process_model_basic_conv
    elif args["type"] == "min_conv":
        process = process_model_conv_minimal
    elif args["type"] == "min_pool":
        process = process_model_min_pool
    elif args["type"] == "cifar":
        process = process_model_cifar
    elif args["type"] == "cifar_advanced":
        process = process_model_cifar_advanced
    else:
        print("unknown model architecture descriptor. Exiting...")
        sys.exit(0)


    res = process(nodes, img, weights, filters, biases, Ms)
    print("========= inference result of image " + str(args["image"]) + " =========")
    print(f"EXPECTED {label}, RETURNED {res}")

    if args["layer"] >= 0:
        print("========= intermediate nodes in layer " + str(args["layer"]) + " =========")
        print_nodes(nodes, args["layer"], args["num_nodes"], args["offset"])

    if args["top10"]:
        print("========= top10 ============")
        count = 0
        num = 10

        for i in range(num):
            inp, label = load_input(i, "cifar")
            nodes = init_nodes(bas, layers)
            res = process(nodes, inp, weights, filters, biases, Ms)

            print(f"EXPECTED {label}, RETURNED {res}")
            if res == label:
                count += 1

        print(f"{100 * count / num}% correct.")
