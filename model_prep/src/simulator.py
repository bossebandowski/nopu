# standard libs
import argparse
import enum

# local
import models

# 3rd party
import tensorflow as tf
import numpy as np

# constants
MODELS = models.DESCRIPTOR_LIST

def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "-l",
        "--layer",
        type=int,
        default=0,
        help="specify which layer to calculate. Valid inputs depend on the model architecture",
    )
    parser.add_argument(
        "-n",
        "--node",
        type=int,
        default=0,
        help="specify which layer to calculate. Valid inputs depend on the model architecture",
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
    args = vars(parser.parse_args())
    return args

def get_fc_layer_ids(layers):
    fcs = []
    bas = []
    for layer_id in layers.keys():
        if not ";" in layers[layer_id]["name"]:
            if "MatMul" in layers[layer_id]["name"]:
                fcs.append(layer_id)
            elif "BiasAdd" in layers[layer_id]["name"]:
                bas.append(layer_id)
    return fcs, bas

def init_nodes(bas, layers):
    nodes = []
    for ba in bas:
        nodes.append(np.zeros(layers[ba]["tensor"].size, dtype=np.int32))

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

def mac_fc(input, output, weights, layer):
    for in_id in range(len(input)):
        for out_id in range(len(output[layer])):
            in_param = input[in_id]
            weight = weights[layer][in_id, out_id]
            output[layer][out_id] = nodes[layer][out_id] + in_param * weight

def bias_relu(outputs, biases, layer):
    for id in range(len(outputs[layer])):
        outputs[layer][id] = max(outputs[layer][id] + biases[layer][id], 0)

def bias(outputs, biases, layer):
    for id in range(len(outputs[layer])):
        outputs[layer][id] = outputs[layer][id] + biases[layer][id]

def process_model(nodes, img, weights, biases):
    # layer 0
    mac_fc(img, nodes, weights, 0)
    bias_relu(nodes, biases, 0)
    
    mac_fc(nodes[0], nodes, weights, 1)
    bias_relu(nodes, biases, 1)
    
    mac_fc(nodes[1], nodes, weights, 2)
    bias(nodes, biases, 2)

    return np.argmax(nodes[-1])


if __name__ == "__main__":
    args = parse_args()

    interpreter = tf.lite.Interpreter(model_path=args["model"])
    interpreter.allocate_tensors()

    tensor_details = interpreter.get_tensor_details()
    layers = {}

    for dict in tensor_details:
        layers[dict["index"]] = {"name": dict["name"], "tensor": interpreter.tensor(dict["index"])()}

    fcs, bas = get_fc_layer_ids(layers)
    nodes = init_nodes(bas, layers)
    img, label = load_input(args["image"])
    weights = load_weights(fcs, layers)
    biases = load_biases(bas, layers)

    res = process_model(nodes, img, weights, biases)

    for l_id in range(len(nodes)):
        print("==========")
        for i in range(len(nodes[l_id])):
            print(i, int(nodes[l_id][i]))

    print(f"EXPECTED {label}, RETURNED {res}")
    
    