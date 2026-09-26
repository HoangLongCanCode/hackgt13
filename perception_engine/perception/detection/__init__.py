"""Plan section 7 vehicle / road-object detection block. See README.md."""
from perception.detection.detector import (COCO_MAPPABLE_BDD, COCO_TO_BDD, DEFAULT_PRESET, DEFAULT_PRESET_BY_BACKEND,
                                           PRESETS, Detector, apply_rider_heuristic)

__all__ = ["Detector", "PRESETS", "DEFAULT_PRESET", "DEFAULT_PRESET_BY_BACKEND", "COCO_TO_BDD", "COCO_MAPPABLE_BDD",
           "apply_rider_heuristic"]
