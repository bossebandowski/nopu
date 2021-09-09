"""
https://debuggercafe.com/ssdlite-mobilenetv3-backbone-object-detection-with-pytorch-and-torchvision/
"""


import torch
import cv2
import detect_utils
from PIL import Image

def detect_objects(image, img_id, model, device, threshold=0.5, save=False):
    boxes, class_ids, class_names, scores = detect_utils.predict(image, model, device, threshold)
    if save:
        # draw bounding boxes
        image = detect_utils.draw_boxes(boxes, class_names, class_ids, image)
        save_name = f"img{img_id}"
        cv2.imwrite(f"outputs/{save_name}.jpg", image)
    return boxes, class_ids, class_names, scores        
