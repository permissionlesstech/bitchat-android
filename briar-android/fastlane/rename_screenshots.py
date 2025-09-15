#!/usr/bin/env python3

# Author: Torsten Grote
# License: GPLv3 or later

import os
import re
import glob

METADATA_PATH = 'metadata/android'
GLOB = '/*/images/phoneScreenshots/*.png'

REGEX = re.compile(r'(^\w+)_\d{13}\.png$')
REGEX_IN_FILE = re.compile(r'(\w+)_\d{13}\.png', re.MULTILINE)
PATH = os.path.dirname(os.path.realpath(__file__))


def main():
    for path in glob.glob("%s%s" % (os.path.join(PATH, METADATA_PATH), GLOB)):
        filename = os.path.basename(path)
        match = REGEX.match(filename)
        if match:
            directory = os.path.dirname(path)
            new_filename = "%s.png" % match.group(1)
            new_path = os.path.join(directory, new_filename)
            os.rename(path, new_path)
            print("Renaming\n  %s\nto\n  %s\n" % (path, new_path))
        else:
            print("Warning: Path did not match %s" % path)

    # rename fields also in screenshot overview file
    overview = os.path.join(PATH, METADATA_PATH, 'screenshots.html')
    with open(overview, 'r') as f:
        file_data = f.read()

    file_data = REGEX_IN_FILE.sub(r'\1.png', file_data)

    with open(overview, 'w') as f:
        f.write(file_data)


if __name__ == "__main__":
    main()
