import argparse
import json
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("osm")
parser.add_argument("output")
parser.add_argument("--north-coast", action="store_true", help="Fill the bay north and west of the San Francisco shoreline")
args = parser.parse_args()
root = ET.parse(args.osm).getroot()
nodes = {node.attrib["id"]: (float(node.attrib["lon"]), float(node.attrib["lat"]))
         for node in root.findall("node")}
features = []
coast = []
for way in root.findall("way"):
    tags = {tag.attrib["k"]: tag.attrib["v"] for tag in way.findall("tag")}
    points = [nodes.get(ref.attrib["ref"]) for ref in way.findall("nd")]
    if len(points) < 2 or any(point is None for point in points):
        continue
    highway = tags.get("highway")
    closed = len(points) > 3 and points[0] == points[-1]
    if args.north_coast and tags.get("natural") == "coastline":
        if closed:
            features.append(["island", [round(value, 6) for point in points for value in point]])
        else:
            coast.append(points)
        continue
    if closed and (tags.get("natural") == "water" or tags.get("waterway") == "riverbank"):
        layer = "water"
    elif tags.get("waterway") in {"river", "stream", "canal"}:
        layer = "stream"
    elif closed and (tags.get("leisure") in {"park", "garden"} or tags.get("landuse") in {"grass", "forest", "recreation_ground"} or tags.get("natural") in {"wood", "grassland"}):
        layer = "green"
    elif closed and tags.get("historic") in {"archaeological_site", "tomb"}:
        layer = "heritage"
    elif highway in {"primary", "secondary", "tertiary", "primary_link", "secondary_link", "tertiary_link", "trunk", "trunk_link"}:
        layer = "main"
    elif highway in {"residential", "living_street", "unclassified", "service"}:
        layer = "street"
    elif highway in {"footway", "pedestrian", "path", "cycleway", "steps"}:
        layer = "path"
    else:
        continue
    feature = [layer, [round(value, 6) for point in points for value in point]]
    if layer == "main" and tags.get("name:ko", tags.get("name")):
        feature.append(tags.get("name:ko", tags.get("name")))
    features.append(feature)

if coast:
    by_start = {line[0]: line for line in coast}
    ends = {line[-1] for line in coast}
    starts = [line for line in coast if line[0] not in ends]
    line = max(starts, key=lambda part: part[0][0]).copy()
    while line[-1] in by_start:
        line.extend(by_start.pop(line[-1])[1:])
    east, west = line[0], line[-1]
    sea = line + [(-122.52, west[1]), (-122.52, 37.84), (-122.41, 37.84), (-122.41, east[1]), east]
    features.insert(0, ["sea", [round(value, 6) for point in sea for value in point]])

with open(args.output, "w", encoding="utf-8") as target:
    json.dump(features, target, ensure_ascii=False, separators=(",", ":"))
print(len(features), "mapped ways")
