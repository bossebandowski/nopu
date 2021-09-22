from __future__ import print_function
import argparse
import torch
import torch.nn as nn
import torch.nn.functional as F
import torch.optim as optim
from torchvision import datasets, transforms
from torch.optim.lr_scheduler import StepLR
import os
import torch.quantization
from mnist_model import Net
import train_mnist
import cv2
import numpy as np

BASE_PATH = "/home/bossebandowski/thesis"
DEFAULT_MODEL_PATH = os.path.join(BASE_PATH, "models/mnist_cnn.pt")
BACKEND = "fpgemm"


def parse_args():
    # Training settings
    parser = argparse.ArgumentParser(description="PyTorch MNIST Example")
    parser.add_argument(
        "--batch-size",
        type=int,
        default=64,
        metavar="N",
        help="input batch size for training (default: 64)",
    )
    parser.add_argument(
        "--test-batch-size",
        type=int,
        default=1000,
        metavar="N",
        help="input batch size for testing (default: 1000)",
    )
    parser.add_argument(
        "--epochs",
        type=int,
        default=14,
        metavar="N",
        help="number of epochs to train (default: 14)",
    )
    parser.add_argument(
        "--lr",
        type=float,
        default=1.0,
        metavar="LR",
        help="learning rate (default: 1.0)",
    )
    parser.add_argument(
        "--gamma",
        type=float,
        default=0.7,
        metavar="M",
        help="Learning rate step gamma (default: 0.7)",
    )
    parser.add_argument(
        "--no-cuda", action="store_true", default=False, help="disables CUDA training"
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        default=False,
        help="quickly check a single pass",
    )
    parser.add_argument(
        "--seed", type=int, default=1, metavar="S", help="random seed (default: 1)"
    )
    parser.add_argument(
        "--log-interval",
        type=int,
        default=10,
        metavar="N",
        help="how many batches to wait before logging training status",
    )
    parser.add_argument(
        "--save-model",
        action="store_true",
        default=False,
        help="For Saving the current Model",
    )
    args = parser.parse_args()

    return args


def static_quantization(model):
    model.qconfig = torch.quantization.get_default_qconfig(BACKEND)
    print(model.qconfig)
    quantized_model = torch.quantization.prepare(model, inplace=False)
    quantized_model = torch.quantization.convert(quantized_model, inplace=False)
    return quantized_model


def print_size_of_model(model):
    torch.save(model.state_dict(), "temp.p")
    print("Size (MB):", os.path.getsize("temp.p") / 1e6)
    os.remove("temp.p")


def extract_weights(model, path=os.path.join(BASE_PATH, "tmp/model.c")):
    # for name, param in model.named_parameters():
    #    print(name, param.size())
    for k, v in model.state_dict().items():
        if "conv" in k:
            print(k, "\n", type(v))
        elif "fc" in k:
            print(k, "\n", type(v))
        """
        
    for layer in model.children():
        print(layer._get_name())
        try:
            print(layer._packed_params)
        except:
            print("no packed params attribute")
    
        """


def eval_qualitative(model, device, num_examples=10):
    examples = train_mnist.get_mnist_examples(num_examples)
    i = 0
    with torch.no_grad():
        for img, label in examples:
            # rearrange axes and convert to uint
            np_image = (np.moveaxis(np.array(img), [0], [2]) * 255).astype(np.uint8)
            # save in tmp directory
            cv2.imwrite(os.path.join(BASE_PATH, f"tmp/mnist_{i}.jpg"), np_image)
            i += 1
            # run inference
            img = img.to(device)
            output = model(img[None, ...])
            print(
                f"predicted: {int(output.argmax(dim=1, keepdim=True))}, label: {label}"
            )


if __name__ == "__main__":
    args = parse_args()

    use_cuda = not args.no_cuda and torch.cuda.is_available()
    print("use cuda?", use_cuda)

    torch.manual_seed(args.seed)

    device = torch.device("cuda" if use_cuda else "cpu")

    model = train_mnist.load_model(device)
    model.eval()

    quantized_model = static_quantization(model).eval()
    print_size_of_model(model)
    print_size_of_model(quantized_model)

    _, test_loader = train_mnist.load_data()

    train_mnist.test(model, device, test_loader)
    train_mnist.test(quantized_model, device, test_loader)

    print("===========================================")
    # extract_weights(quantized_model)
    print("===========================================")
