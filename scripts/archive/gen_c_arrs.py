import tensorflow as tf
import numpy as np
from tensorflow.python.keras.layers import Lambda

footer = "};"
network_path = "mnist_model_quant.tflite"

interpreter = tf.lite.Interpreter(model_path=str(network_path))
interpreter.allocate_tensors()


input_details = interpreter.get_input_details()
output_details = interpreter.get_output_details()

print("== Input details ==")
print("name:", input_details[0]['name'])
print("shape:", input_details[0]['shape'])
print("type:", input_details[0]['dtype'])
print("\n== Output details ==")
print("name:", output_details[0]['name'])
print("shape:", output_details[0]['shape'])
print("type:", output_details[0]['dtype'])

tensor_details = interpreter.get_tensor_details()

for dict in tensor_details:
    i = dict['index']
    tensor_name = dict['name']
    scales = dict['quantization_parameters']['scales']
    zero_points = dict['quantization_parameters']['zero_points']
    tensor = interpreter.tensor(i)()

    print(i, tensor_name, dict["shape"])

def get_variable(interpreter, index, transposed=False):
    var = interpreter.get_tensor(index)
    if transposed:
        var = np.transpose(var, (1, 0))
    return var

def dense(params):
    X, W, b = params
    return tf.add(tf.matmul(X, W), b)

def network(input_shape, interpreter):
    W1 = get_variable(interpreter, 4, transposed=True)
    b1 = get_variable(interpreter, 1)
    W2 = get_variable(interpreter, 5, transposed=True)
    b2 = get_variable(interpreter, 2)
    """
    inputs = tf.keras.layers.Input(shape=input_shape)

    # Flatten layer
    x_0 = tf.keras.layers.Flatten()(inputs)

    # First fully connected with relu
    x_1 = Lambda(dense)((x_0, W1, b1))
    x_1relu = tf.nn.relu(x_1)

    # second fully connected
    x_2 = Lambda(dense)((x_1relu, W2, b2))

    # Finally the softmax
    x_softmax = tf.keras.activations.softmax(x_2)
    """
    return (W1, W2, b1, b2) # tf.keras.models.Model(inputs=inputs, outputs=[x_2])

w1, w2, b1, b2 = network((28, 28), interpreter)

print("Data types:")
print("weights 1:", w1.dtype)
print("weights 2:", w2.dtype)
print("biases 1:", b1.dtype)
print("biases 2:", b2.dtype)

print("Shapes:")
print("weights 1:", w1.shape)
print("weights 2:", w2.shape)
print("biases 1:", b1.shape)
print("biases 2:", b2.shape)

def save_weights(fname, weights):
    len_x, len_y = weights.shape
    out = "const int8_t " + fname + "[" + str(len_x*len_y) + "] = {\n\t"
    for x in range(len_x):
        for y in range(len_y):
            out += str(weights[x, y]) + ",\n\t"
    out = out[:-3] + "\n" + footer
    with open(fname + ".txt", "w") as f:
        f.write(out)

def save_biases(fname, biases):
    out = "const int32_t " + fname + "[" + str(len(biases)) + "] = {\n\t"

    for x in range(len(biases)):
        out += str(biases[x]) + ",\n\t"
    out = out[:-3] + "\n" + footer
    with open(fname + ".txt", "w") as f:
        f.write(out)

save_weights("weights_1", w1)
save_weights("weights_2", w2)

save_biases("biases_1", b1)
save_biases("biases_2", b2)

mnist = tf.keras.datasets.mnist
(train_images, train_labels), (_, _) = mnist.load_data()

def save_example_img(idx):
    img = train_images[idx]
    label = train_labels[idx]
    len_x, len_y = img.shape
    out = "const int8_t " + "img_" + str(label) + "[" + str(len_x*len_y) + "] = {\n\t"
    for x in range(len_x):
        for y in range(len_y):
            out += str(img[x, y]) + ",\n\t"
    out = out[:-3] + "\n" + footer
    with open("img_#" + str(idx) + "_expected_" + str(label) + ".txt", "w") as f:
        f.write(out)

for i in range(100):
    save_example_img(i)