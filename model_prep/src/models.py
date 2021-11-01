import tensorflow as tf

DESCRIPTOR_LIST = ["basic_fc", "three_fc", "basic_conv", "min_conv"]

basic_fc_model = tf.keras.Sequential(
    [
        tf.keras.layers.Flatten(input_shape=(28, 28, 1)),
        tf.keras.layers.Dense(100, activation="relu"),
        tf.keras.layers.Dense(12),
    ]
)

three_fc_model = tf.keras.Sequential(
    [
        tf.keras.layers.Flatten(input_shape=(28, 28, 1)),
        tf.keras.layers.Dense(32, activation="relu"),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(12),
    ]
)

basic_conv_model = tf.keras.Sequential(
    [
        tf.keras.layers.Conv2D(16, (3, 3), activation="relu", input_shape=(28, 28, 1)),
        tf.keras.layers.MaxPooling2D((2, 2)),
        tf.keras.layers.Conv2D(16, (3, 3), activation="relu"),
        tf.keras.layers.MaxPooling2D((2, 2)),
        tf.keras.layers.Flatten(),
        tf.keras.layers.Dense(64, activation="relu"),
        tf.keras.layers.Dense(12),
    ]
)

minimal_conv_model = tf.keras.Sequential(
    [
        tf.keras.layers.Conv2D(16, (3, 3), activation="relu", input_shape=(28, 28, 1)),
        tf.keras.layers.Flatten(),
        tf.keras.layers.Dense(12),
    ]
)


def get_model(descriptor):
    if descriptor == "basic_conv":
        return basic_conv_model
    elif descriptor == "basic_fc":
        return basic_fc_model
    elif descriptor == "three_fc":
        return three_fc_model
    elif descriptor == "min_conv":
        return minimal_conv_model
    else:
        raise ValueError("Model descriptor unknown!")
