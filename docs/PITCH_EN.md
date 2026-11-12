# Kkaeugi

An app for Hyundai's Pleos Connect platform. The car estimates cabin air from the number of people aboard, asks first, then switches to outside air.

## Problem

On recirculation, exhaled CO2 builds up faster with more people aboard. One experiment measured about 1,600 ppm with one occupant and 6,600 ppm with four after 30 minutes (Mathur, SAE 2020).

## What it does

- Counts occupants from seat and belt signals. No CO2 sensor.
- At an estimated 2,000 ppm it asks one short question. A solo driver gets two minutes of outside air, then the old settings. With four aboard it asks to keep outside air on.
- After three yeses in a row it asks once, at arrival, whether to just announce next time. From then on it announces and waits five seconds for a "no", but only while its screen is showing. Otherwise it asks.
- It stays quiet after two refusals (an "outside air" button stays on screen), near arrival, and while the car manages recirculation itself.
- Same pattern, second use: after the car's drowsiness warning it offers cool air and the next rest stop.

## Already out there

Kia and Voyah switch to outside air on a timer. A 2018 US patent application estimates CO2 from occupants. None of them ask. Kkaeugi starts with consent per action and only announces once the driver hands that over.

## Limits

The ppm figure is an estimate. It cannot see tunnels or outdoor air quality, so it asks. Not verified on the Pleos emulator yet. The repository has a browser simulator running the same engine.
