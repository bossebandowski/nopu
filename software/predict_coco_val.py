"""
https://medium.com/howtoai/pytorch-torchvision-coco-dataset-b7f5e8cad82
"""

import torchvision.datasets as dset
import torchvision
import torch
from detect_image import detect_objects
import json
import time
import argparse
import os

# Constants
path2data = "../coco/val2017"
path2json = "../coco/annotations/instances_val2017.json"
standard_models = ['mobilenetv3-large', 'mobilenetv3-small', 'faster-rcnn']
coco_val = dset.CocoDetection(root=path2data, annFile=path2json)

# define the computation device
device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')


# small helper functions
def get_model(model_path):
    # load the model
    if not model_path in standard_models:
        model = torch.load(model_path)
    else:
        if model_path == standard_models[0]:
            model = torchvision.models.detection.ssdlite320_mobilenet_v3_large(pretrained=True)
        elif model_path == standard_models[1]:
            model = torchvision.models.detection.ssdlite320_mobilenet_v3_small(pretrained=True)
        elif model_path == standard_models[2]:
            model = torchvision.models.detection.fasterrcnn_resnet50_fpn(pretrained=True)

    # load the model onto the computation device
    model = model.eval().to(device)
    return model



# script
if __name__ == "__main__":
    # construct the argument parser
    parser = argparse.ArgumentParser()
    parser.add_argument('-n', '--num_images', default=5000, type=int, 
                        help='number of images to be inferred')
    parser.add_argument('-t', '--threshold', default=0.5, type=float,
                        help='detection threshold')
    parser.add_argument('-m', '--model', default='mobilenetv3-large',
                        help=f'path to custom model or one of the following pretrained ones:\n{standard_models}')
    parser.add_argument('-s', '--save', default=False, type=bool,
                        help='save annotated output images for visual inspection in ./outputs')
    args = vars(parser.parse_args())

    if args['model'] in standard_models:
        model_name = args['model']
    else:
        model_name = os.path.basename(args['model'])
    SAVE_NAME = f"results_{model_name}_t{args['threshold']}_n{args['num_images']}"

    print("===========================================================")
    print(f'Starting validation on COCO with the following parameters')
    print('\tNumber of samples:\t', args['num_images'])
    print('\tmodel:\t\t\t', args['model'])
    print('\tthreshold:\t\t', args['threshold'])
    print('\tsave outputs:\t\t', args['save'])
    print("===========================================================")

    model = get_model(args['model'])

    t0 = time.time()

    # instantiate output list
    results = []
    err_count = 0

    # iterate over all images in validation set (or user-defined count)
    for i in range(args['num_images']):

        # extract tuple into img and ground truth
        img, ground_truth = coco_val[i]

        # run inference on image
        boxes, ids, _, scores = detect_objects(img, i, model, device, threshold=args['threshold'], save=args['save'])

        # every detected instance gets their own dict
        if not ground_truth:
            pass
        else:
            for detection_id in range(len(boxes)):
                bbox = boxes[detection_id].tolist()
                # convert xyxy to xywh
                bbox[2] = bbox[2] - bbox[0]
                bbox[3] = bbox[3] - bbox[1]

                category_id = ids[detection_id]
                score = scores[detection_id]
                if ground_truth:
                    results.append({'image_id': ground_truth[0]['image_id'], 'category_id': int(category_id), 'bbox': bbox, 'score': float(score)})

    # convert output to json and save to file
    with open(f"outputs/{SAVE_NAME}.json", "w") as f:
        f.write(json.dumps(results))

    t1 = time.time()
    print(f"Done. Time elapsed: {t1 - t0}s. Number of images: {args['num_images']}, IPS: {args['num_images']/(t1-t0)}")
    print(f"Results saved to outputs/{SAVE_NAME}.json")