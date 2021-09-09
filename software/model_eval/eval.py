import matplotlib.pyplot as plt
from pycocotools.coco import COCO
from pycocotools.cocoeval import COCOeval
import numpy as np
import skimage.io as io
import argparse

# construct the argument parser
parser = argparse.ArgumentParser()
parser.add_argument('results', 
                    help='path to results .json file')
args = vars(parser.parse_args())


annType = 'bbox'
prefix = 'instances'

#initialize COCO ground truth api
dataDir='../../coco'
dataType='val2017'
annFile = '%s/annotations/%s_%s.json'%(dataDir,prefix,dataType)
cocoGt=COCO(annFile)

#initialize COCO detections api
resFile=args['results']
cocoDt=cocoGt.loadRes(resFile)

imgIds=sorted(cocoGt.getImgIds())

# running evaluation
cocoEval = COCOeval(cocoGt,cocoDt,annType)
cocoEval.params.imgIds  = imgIds
cocoEval.evaluate()
cocoEval.accumulate()
cocoEval.summarize()