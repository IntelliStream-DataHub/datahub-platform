#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later

import json
import re
import subprocess
import sys


def replace_special_characters(s):
    return re.sub(r'[^a-zA-Z0-9]', '_', s)


class Label:

    def __init__(self, name: str):
        self.name = name

    def __repr__(self):
        return self.__str__()

    def __str__(self):
        return f"{self.name}"

    def to_dict(self):
        return {
            "name": self.name
        }


class Asset:

    def __init__(self):
        self.name = None
        self.external_id = None
        self.description = None
        self.labels = []
        self.metadata = {}

    def set_name(self, name: str):
        self.name = name
        return self

    def set_external_id(self, external_id: str):
        self.external_id = replace_special_characters(external_id)
        return self

    def set_description(self, description: str):
        self.description = description
        return self

    def add_label(self, label: str):
        self.labels.append(Label(label))

    def add_metadata_entry(self, key: str, value: str):
        self.metadata[key] = value

    def __repr__(self):
        return self.__str__()

    def __str__(self):
        return (
            f"\nAsset(\n"
            f"  name: {self.name}\n"
            f"  external_id: {self.external_id}\n"
            f"  labels: {self.labels}\n"
            f"  metadata: {self.metadata}\n"
            f"  description: {self.description}\n"
            f")"
        )

    def to_dict(self):
        return {
            "name": self.name,
            "externalId": self.external_id,
            "labels": [l.to_dict() for l in self.labels],
            "metadata": self.metadata,
            "description": self.description
        }


class StorageDevice:

    def __init__(self):
        self.type = None
        self.protocol = None
        self.size = None
        self.serial = None
        self.logical_name = None
        self.wwn = None
        self.firmware = None

    def set_type(self, type: str):
        self.type = type
        return self

    def set_protocol(self, protocol: str):
        self.protocol = protocol
        return self

    def set_serial(self, serial: str):
        self.serial = re.sub(r'\s+', ' ', serial)
        return self

    def set_size(self, size: str):
        self.size = size
        return self

    def set_logical_name(self, logical_name: str):
        self.logical_name = logical_name
        return self

    def set_wwn(self, wwn: str):
        self.wwn = wwn
        return self

    def set_firmware(self, firmware: str):
        self.firmware = firmware
        return self

    def __repr__(self):
        return self.__str__()

    def __str__(self):
        return (
            f"\nStorageDevice(\n"
            f"  type: {self.type}\n"
            f"  protocol: {self.protocol}\n"
            f"  serial: {self.serial}\n"
            f"  size: {self.size}\n"
            f"  logical_name: {self.logical_name}\n"
            f"  wwn: {self.wwn}\n"
            f"  firmware: {self.firmware}\n"
            f")"
        )


def find_size_and_wwn(storage_device: StorageDevice):
    cmd_query = ("lsblk -d | grep " + storage_device.logical_name +
                 " | awk '{print $1 \" \" $4}' | xargs -I % echo \"% $(ls -la /dev/disk/by-id/ | grep -w " +
                 storage_device.logical_name + " | grep wwn | awk '{print $9;}')\""
                 )
    disk_info = subprocess.run(cmd_query, shell=True, stdout=subprocess.PIPE, text=True).stdout.split(" ")
    storage_device.set_size(disk_info[1]).set_wwn(disk_info[2].replace("\n", ""))


server_name = ""
external_id = ""
if len(sys.argv) > 2:
    server_name = sys.argv[1]
    from_external_id = sys.argv[2]
elif len(sys.argv) < 2:
    sys.exit("Missing servername argument!")
elif len(sys.argv) < 3:
    sys.exit("Missing from from_external_id argument!")

all_hard_drives_cmd = "lshw -class disk -class storage -json"
result = subprocess.run(all_hard_drives_cmd, shell=True, stdout=subprocess.PIPE, text=True)
lshw_data = json.loads(result.stdout)

storage_devices = []

for item in lshw_data:
    if item['class'] == 'disk' and \
            'description' in item and \
            item['description'] in ["SCSI Disk", "ATA Disk"] and \
            isinstance(item['logicalname'], list) is False:
        storage_device = StorageDevice()
        (storage_device
         .set_serial(item['serial'])
         .set_firmware(item['version'])
         .set_logical_name(item['logicalname'].replace("/dev/", ""))
         )

        if item['description'] == 'SCSI Disk':
            storage_device.set_type("SAS")
        elif item['description'] == 'ATA Disk':
            storage_device.set_type("SATA")

        find_size_and_wwn(storage_device)
        storage_devices.append(storage_device)

sas_hdd_cmd = """lsblk -d | grep 1,7T | awk '{print $1}' | xargs -I % sh -c 'echo "% $(smartctl -a /dev/% | 
grep "Current Drive Temperature:" | awk '"'"'{print $4}'"'"') $(ls -la /dev/disk/by-id/ | grep -w '"'"'%'"'"' | 
grep wwn | awk '"'"'{print $9;}'"'"')"'"""
# result = subprocess.run(sas_hdd_cmd, shell=True, stdout=subprocess.PIPE, text=True)
# print(result.stdout)

assets = []
for s in storage_devices:
    asset = Asset()
    asset.set_name("storage_drive_" + s.serial)
    asset.set_external_id(server_name + "_storage_drive_" + s.serial)
    asset.add_metadata_entry("protocol", s.type)
    asset.add_metadata_entry("logical_name", s.logical_name)
    asset.add_metadata_entry("firmware", s.firmware)
    asset.add_metadata_entry("wwn", s.wwn)
    asset.add_metadata_entry("serial", s.serial)
    asset.add_metadata_entry("size", s.size)
    if s.type in ["SAS", "SATA"]:
        asset.add_label("STORAGE_DRIVE")

        is_rotational_cmd = f"cat /sys/block/{s.logical_name}/queue/rotational"
        output = subprocess.run(is_rotational_cmd, shell=True, stdout=subprocess.PIPE, text=True)
        is_rotational = True if int(output.stdout) == 1 else False
        if is_rotational:
            asset.add_label("HDD")
        else:
            asset.add_label("SSD")
    assets.append(asset)

data = {
    "nodes": [],
    "relations": []
}
for a in assets:
    data["nodes"].append(a.to_dict()),
    data["relations"].append(
        {
            "fromExternalId": from_external_id,
            "toExternalId": a.external_id,
            "relationshipType": a.metadata['protocol'] + "_CONNECTION_TO"
        }
    )

json = json.dumps(data, indent=2)
print(json)
