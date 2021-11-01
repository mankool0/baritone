# Baritone
<p align="center">
  <a href="https://github.com/cabaletta/baritone/releases/"><img src="https://img.shields.io/github/downloads/cabaletta/baritone/total.svg" alt="GitHub All Releases"/></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/MC-1.12.2-brightgreen.svg" alt="Minecraft"/>
</p>

<p align="center">
  <a href="https://travis-ci.com/cabaletta/baritone/"><img src="https://travis-ci.com/cabaletta/baritone.svg?branch=master" alt="Build Status"/></a>
  <a href="https://github.com/cabaletta/baritone/releases/"><img src="https://img.shields.io/github/release/cabaletta/baritone.svg" alt="Release"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-LGPL--3.0%20with%20anime%20exception-green.svg" alt="License"/></a>
  <a href="https://www.codacy.com/app/leijurv/baritone?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=cabaletta/baritone&amp;utm_campaign=Badge_Grade"><img src="https://api.codacy.com/project/badge/Grade/a73d037823b64a5faf597a18d71e3400" alt="Codacy Badge"/></a>
  <a href="https://github.com/cabaletta/baritone/blob/master/CODE_OF_CONDUCT.md"><img src="https://img.shields.io/badge/%E2%9D%A4-code%20of%20conduct-blue.svg?style=flat" alt="Code of Conduct"/></a>
  <a href="https://snyk.io/test/github/cabaletta/baritone?targetFile=build.gradle"><img src="https://snyk.io/test/github/cabaletta/baritone/badge.svg?targetFile=build.gradle" alt="Known Vulnerabilities"/></a>
  <a href="https://github.com/cabaletta/baritone/issues/"><img src="https://img.shields.io/badge/contributions-welcome-brightgreen.svg?style=flat" alt="Contributions welcome"/></a>
  <a href="https://github.com/cabaletta/baritone/issues/"><img src="https://img.shields.io/github/issues/cabaletta/baritone.svg" alt="Issues"/></a>
  <a href="https://github.com/cabaletta/baritone/issues?q=is%3Aissue+is%3Aclosed"><img src="https://img.shields.io/github/issues-closed/cabaletta/baritone.svg" alt="GitHub issues-closed"/></a>
  <a href="https://github.com/cabaletta/baritone/pulls/"><img src="https://img.shields.io/github/issues-pr/cabaletta/baritone.svg" alt="Pull Requests"/></a>
  <a href="https://github.com/cabaletta/baritone/graphs/contributors/"><img src="https://img.shields.io/github/contributors/cabaletta/baritone.svg" alt="GitHub contributors"/></a>
  <a href="https://github.com/cabaletta/baritone/commit/"><img src="https://img.shields.io/github/commits-since/cabaletta/baritone/v1.0.0.svg" alt="GitHub commits"/></a>
  <img src="https://img.shields.io/github/languages/code-size/cabaletta/baritone.svg" alt="Code size"/>
  <img src="https://img.shields.io/github/repo-size/cabaletta/baritone.svg" alt="GitHub repo size"/>
  <img src="https://tokei.rs/b1/github/cabaletta/baritone?category=code" alt="Lines of Code"/>
</p>

<p align="center">
  <a href="http://forthebadge.com/"><img src="https://forthebadge.com/images/badges/built-with-swag.svg" alt="forthebadge"/></a>
  <a href="http://forthebadge.com/"><img src="https://forthebadge.com/images/badges/mom-made-pizza-rolls.svg" alt="forthebadge"/></a>
</p>

A Minecraft pathfinder bot, with mapart capability.

# Getting Started

Here are some links to help to get started:

- [Features](FEATURES.md)

- [Installation & setup](SETUP.md)

- [API Javadocs](https://baritone.leijurv.com/)

- [Settings](https://baritone.leijurv.com/baritone/api/Settings.html#field.detail)

- [Usage (chat control)](USAGE.md)

## Stars over time

[![Stargazers over time](https://starchart.cc/Mankool0/baritone.svg)](https://starchart.cc/Mankool0/baritone)

# API

The API is heavily documented, you can find the Javadocs for the latest release [here](https://baritone.leijurv.com/).
Please note that usage of anything located outside of the ``baritone.api`` package is not supported by the API release
jar.

Below is an example of basic usage for changing some settings, and then pathing to an X/Z goal.

```
BaritoneAPI.getSettings().allowSprint.value = true;
BaritoneAPI.getSettings().primaryTimeoutMS.value = 2000L;

BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ(10000, 20000));
```

# FAQ
## New Commands
```mapbuild``` Uses all the new logic programmed
* A schematica schematic has to be placed for this command to work (mapmatica works aswell)
* make sure material shulkers are placed with gaps inbetween every 3-4 shulkers

```mapstop```
* stops baritone

## Recommend default settings
Mileage may vary, but these are what I have found to work the best for me
```
allowInventory true
assumeWalkOnWater true
acceptableThrowawayItems minecraft:dirt,minecraft:cobblestone,minecraft:stone
blocksToAvoidBreaking crafting_table,furnace,lit_furnace,chest,trapped_chest,standing_sign,wall_sign,white_shulker_box,orange_shulker_box,magenta_shulker_box,light_blue_shulker_box,yellow_shulker_box,lime_shulker_box,pink_shulker_box,gray_shulker_box,silver_shulker_box,cyan_shulker_box,purple_shulker_box,blue_shulker_box,brown_shulker_box,green_shulker_box,red_shulker_box,black_shulker_box
blockReachDistance 3.8
failureTimeoutMS 2000
useSwordToMine false
```

## How is it so fast?

Magic. (Hours of [Mankool's](https://github.com/Mankool0/) enduring excruciating bug fixing, and [Redhawk's](https://github.com/Redhawk18/) endless hours of testing)

