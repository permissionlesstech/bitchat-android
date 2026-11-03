'''
An ad-hoc script to analyze impact of isolines profiles changes
and update regions' profiles according to certain criteria.

Input size files are produced by `ls -sk` of e.g. directory with isolines files.
They should look like (sizes are in KB):

total 11176840
  3892 Abkhazia.isolines
 39472 Afghanistan.isolines
 10904 Albania.isolines
'''


import json
from os import listdir

data = object()
with open('countries-to-generate.json') as f_profiles:
  data = json.load(f_profiles)

profiles = data["countryParams"]

mwms = {}

# Current MWM file sizes
with open('mwm-250622.txt') as f_mwms:
  for line in f_mwms:
    line = line.split(maxsplit=1)
    if line[1][-4:] == "mwm\n":
      mwm = line[1][:-5]
      mwms[mwm] = {"size" : int(line[0]) * 1024}

for e in profiles:
  if e["key"] in mwms:
    mwms[e["key"]]["profile"] = e["value"]["profileName"]
  else:
    print(e["key"], " is missing in mwm sizes file!")


# Current isolines file sizes
with open('isolines-250622.txt') as f_mwms:
  for line in f_mwms:
    line = line.split(maxsplit=1)
    if line[1][-9:] == "isolines\n":
      mwm = line[1][:-10]
      mwms[mwm]["iso_current"] = int(line[0]) * 1024
      mwms[mwm]["base_size"] = mwms[mwm]["size"] - mwms[mwm]["iso_current"]
      mwms[mwm]["iso_current%"] = round(mwms[mwm]["iso_current"] / mwms[mwm]["base_size"] * 100)

with open('isolines-100-f4-s14.txt') as f_mwms:
  for line in f_mwms:
    line = line.split(maxsplit=1)
    if line[1][-9:] == "isolines\n":
      mwm = line[1][:-10]
      if (int(line[0]) > 4):
        mwms[mwm]["iso_100-f4-s14"] = int(line[0]) * 1024
        mwms[mwm]["iso_100-f4-s14%"] = round(mwms[mwm]["iso_100-f4-s14"] / mwms[mwm]["base_size"] * 100)

# New isolines file sizes for the 50-f4-s14 settings (f4 is latLonStepFactor, s14 is simplificationZoom).
# They were produced only for MWMs which have worse profiles now (e.g. poor and extra_small).
with open('isolines-50-f4-s14.txt') as f_mwms:
  for line in f_mwms:
    line = line.split(maxsplit=1)
    if line[1][-9:] == "isolines\n":
      mwm = line[1][:-10]
      mwms[mwm]["iso_50-f4-s14"] = int(line[0]) * 1024
      mwms[mwm]["iso_50-f4-s14%"] = round(mwms[mwm]["iso_50-f4-s14"] / mwms[mwm]["base_size"] * 100)

with open('isolines-50-f3-s14.txt') as f_mwms:
  for line in f_mwms:
    line = line.split(maxsplit=1)
    if line[1][-9:] == "isolines\n":
      mwm = line[1][:-10]
      mwms[mwm]["iso_50-f3-s14"] = int(line[0]) * 1024
      mwms[mwm]["iso_50-f3-s14%"] = round(mwms[mwm]["iso_50-f3-s14"] / mwms[mwm]["base_size"] * 100)

with open('isolines-20-f3-s15.txt') as f_mwms:
  for line in f_mwms:
    line = line.split(maxsplit=1)
    if line[1][-9:] == "isolines\n":
      mwm = line[1][:-10]
      mwms[mwm]["iso_20-f3-s15"] = int(line[0]) * 1024
      mwms[mwm]["iso_20-f3-s15%"] = round(mwms[mwm]["iso_20-f3-s15"] / mwms[mwm]["base_size"] * 100)

print("Action\tMWM\tCurProfile\tCur%ofBase\tNew%ofBase\tIsoSizeMB\tNewMwmSizeMB\tSizeChangeMB\tSizeChange%")

for mwm, sizes in mwms.items():
  if "profile" in sizes:
    if sizes["profile"] in ["poor", "extra_small", "small", "normal"]:
      if "iso_20-f3-s15%" in sizes:
        new_size_20 = round((sizes["base_size"] + sizes["iso_20-f3-s15"]) / 1024 / 1024)
        new_size_50 = round((sizes["base_size"] + sizes["iso_50-f4-s14"]) / 1024 / 1024)

        # Upgrade to 20m step if
        # mwm size growth <= 15% or new size is small anyway < 20MB or isolines take < 25% of mwm size
        if ((sizes["iso_20-f3-s15%"] < 30 or new_size_20 < 20 or (sizes["iso_20-f3-s15"]-sizes["iso_current"]) <= sizes["size"]*0.15) and new_size_20 < 500):
          print("UP-20-f3-s15\t%s\t%s\t%d\t%d\t%d\t%d\t%d\t%d" % (mwm, sizes["profile"], sizes["iso_current%"], sizes["iso_20-f3-s15%"], round(sizes["iso_20-f3-s15"]/1024/1024), new_size_20, round((sizes["iso_20-f3-s15"] - sizes["iso_current"])/1024/1024), round(100 * (sizes["iso_20-f3-s15"] - sizes["iso_current"]) / sizes["size"])))
          sizes["new_profile"] = "high_f3"

        # Upgrade to 50m step if
        # mwm size growth <= 30% or new size is small anyway < 30MB or isolines take < 40% of mwm size
        elif (sizes["profile"] in ["poor", "extra_small"] and (sizes["iso_50-f4-s14%"] < 40 or new_size_50 < 30 or (sizes["iso_50-f4-s14"]-sizes["iso_current"]) <= sizes["size"]*0.30) and new_size_50 < 500):
          print("NO-20-CHANGE\t%s\t%s\t%d\t%d\t%d\t%d\t%d\t%d" % (mwm, sizes["profile"], sizes["iso_current%"], sizes["iso_20-f3-s15%"], round(sizes["iso_20-f3-s15"]/1024/1024), new_size_20, round((sizes["iso_20-f3-s15"] - sizes["iso_current"])/1024/1024), round(100 * (sizes["iso_20-f3-s15"] - sizes["iso_current"]) / sizes["size"])))
          print("UP-50-f4-s14\t%s\t%s\t%d\t%d\t%d\t%d\t%d\t%d" % (mwm, sizes["profile"], sizes["iso_current%"], sizes["iso_50-f4-s14%"], round(sizes["iso_50-f4-s14"]/1024/1024), new_size_50, round((sizes["iso_50-f4-s14"] - sizes["iso_current"])/1024/1024), round(100 * (sizes["iso_50-f4-s14"] - sizes["iso_current"]) / sizes["size"])))
          sizes["new_profile"] = "extra_small"

        elif sizes["profile"] in ["poor", "extra_small"]:
          new_size_100 = round((sizes["base_size"] + sizes["iso_100-f4-s14"]) / 1024 / 1024)
          print("NO-50-CHANGE\t%s\t%s\t%d\t%d\t%d\t%d\t%d\t%d" % (mwm, sizes["profile"], sizes["iso_current%"], sizes["iso_50-f4-s14%"], round(sizes["iso_50-f4-s14"]/1024/1024), new_size_50, round((sizes["iso_50-f4-s14"] - sizes["iso_current"])/1024/1024), round(100 * (sizes["iso_50-f4-s14"] - sizes["iso_current"]) / sizes["size"])))

          # "Downgrade" from extra_small 100-f1-s14 to poor 100-f4-s14 (somewhat more smoothed lines and filtered small knobs; but reduce file size)
          if sizes["profile"] == "extra_small":
            print("DOWN-100-f4-s14\t%s\t%s\t%d\t%d\t%d\t%d\t%d\t%d" % (mwm, sizes["profile"], sizes["iso_current%"], sizes["iso_100-f4-s14%"], round(sizes["iso_100-f4-s14"]/1024/1024), new_size_100, round((sizes["iso_100-f4-s14"] - sizes["iso_current"])/1024/1024), round(100 * (sizes["iso_100-f4-s14"] - sizes["iso_current"]) / sizes["size"])))
            sizes["new_profile"] = "poor"

        elif sizes["profile"] in ["small", "normal"]:
          print("NO-20-CHANGE\t%s\t%s\t%d\t%d\t%d\t%d\t%d\t%d" % (mwm, sizes["profile"], sizes["iso_current%"], sizes["iso_20-f3-s15%"], round(sizes["iso_20-f3-s15"]/1024/1024), new_size_20, round((sizes["iso_20-f3-s15"] - sizes["iso_current"])/1024/1024), round(100 * (sizes["iso_20-f3-s15"] - sizes["iso_current"]) / sizes["size"])))

      else:
        print(mwm, "is missing in isolines sizes file!")


for e in profiles:
  if e["key"] in mwms:
    if "new_profile" in mwms[e["key"]]:
      e["value"]["profileName"] = mwms[e["key"]]["new_profile"]
  else:
    print(e["key"], " is missing in mwm sizes file!")

with open('countries-to-generate.json', "w") as f_out:
  json.dump(data, f_out, indent=4)
