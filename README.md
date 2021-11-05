# Baritone
<p align="center">
  <a href="https://github.com/cabaletta/baritone/releases/"><img src="https://img.shields.io/github/downloads/cabaletta/baritone/total.svg" alt="GitHub All Releases"/></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/MC-1.12.2-brightgreen.svg" alt="Minecraft"/>
<!---  <img src="https://img.shields.io/badge/MC-1.13.2-brightgreen.svg" alt="Minecraft"/>
        <img src="https://img.shields.io/badge/MC-1.14.4-brightgreen.svg" alt="Minecraft"/>
        <img src="https://img.shields.io/badge/MC-1.15.2-brightgreen.svg" alt="Minecraft"/>
        <img src="https://img.shields.io/badge/MC-1.16.5-brightgreen.svg" alt="Minecraft"/> -->
</p>

<p align="center">
  <a href="https://travis-ci.com/cabaletta/baritone/"><img src="https://travis-ci.com/cabaletta/baritone.svg?branch=master" alt="Build Status"/></a>
  <a href="https://github.com/cabaletta/baritone/releases/"><img src="https://img.shields.io/github/release/cabaletta/baritone.svg" alt="Release"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-LGPL--3.0%20with%20anime%20exception-green.svg" alt="License"/></a>
  <a href="https://www.codacy.com/app/leijurv/baritone?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=cabaletta/baritone&amp;utm_campaign=Badge_Grade"><img src="https://api.codacy.com/project/badge/Grade/a73d037823b64a5faf597a18d71e3400" alt="Codacy Badge"/></a>
  <a href="https://github.com/cabaletta/baritone/blob/master/CODE_OF_CONDUCT.md"><img src="https://img.shields.io/badge/%E2%9D%A4-code%20of%20conduct-blue.svg?style=flat" alt="Code of Conduct"/></a>
  <a href="https://github.com/mankool0/baritone/tree/highwayBuilding"><img src="https://img.shields.io/badge/contributions-welcome-brightgreen.svg?style=flat" alt="Contributions welcome"/></a>
  <a href="https://github.com/mankool0/baritone/tree/highwayBuilding"><img src="https://img.shields.io/github/issues/mankool0/baritone/tree/highwayBuilding.svg" alt="Issues"/></a>
  <a href="https://github.com/mankool0/baritone/issues?q=is%3Aclosed+label%3AhighwayBuilder+"><img src="https://img.shields.io/github/issues-closed/mankool0/baritone.svg" alt="GitHub issues-closed"/></a>
  <a href="https://github.com/mankool0/baritone/pulls/"><img src="https://img.shields.io/github/issues-pr/mankool0/baritone.svg" alt="Pull Requests"/></a>
  <a href="https://github.com/mankool0/baritone/commit/"><img src="https://img.shields.io/github/commits-since/mankool0/baritone/v1.0.0.svg" alt="GitHub commits"/></a>
  <img src="https://img.shields.io/github/languages/code-size/cabaletta/baritone.svg" alt="Code size"/>
  <img src="https://img.shields.io/github/repo-size/cabaletta/baritone.svg" alt="GitHub repo size"/>
  <img src="https://tokei.rs/b1/github/mankool0/baritone?category=code" alt="Lines of Code"/>
</p>


<p align="center">
  <a href="http://forthebadge.com/"><img src="https://forthebadge.com/images/badges/built-with-swag.svg" alt="forthebadge"/></a>
  <a href="http://forthebadge.com/"><img src="https://forthebadge.com/images/badges/mom-made-pizza-rolls.svg" alt="forthebadge"/></a>
</p>

A Minecraft pathfinder bot.

A custom fork of baritone built for mining and paving highways on anarchy servers

# Getting Started

Here are some links to help to get started:

- [Features](FEATURES.md)

- [Installation & setup](SETUP.md)

- [API Javadocs](https://baritone.leijurv.com/)

- [Settings](https://baritone.leijurv.com/baritone/api/Settings.html#field.detail)

- [Usage (chat control)](USAGE.md)

# My Personal Baritone Settings

```
allowInventory true
blockPlacementPenalty 100.0
jumpPenalty 2.0
buildIgnoreBlocks standing_sign,wall_sign,torch,bedrock,portal
blockReachDistance 4.0
avoidance true
mobAvoidanceCoefficient 2.0
breakFromAbove true
goalBreakFromAbove true
useSwordToMine false
```

For possible settings you can change in the bot look through here: [settings](https://github.com/mankool0/baritone/blob/highwayBuilding/src/api/java/baritone/api/Settings.java#L1210)

All of the settings directly related to highway building start with highway and comments should be self explanatory. If not I can elaborate.

# Tips
• To avoid issues with eating gapples when minecraft is running in the background press F3 + P and make sure sure in chat you see [Debug]: Pause on lost focus: disabled
This will disable the menu screen from showing up and tabbing out.

• Disable forced auto totem if paving as the bot needs access to your offhand. It will put whatever item that was in the offhand back after mining the echests

• Disable Jesus module

• Enable Auto DC on low HP

• Enable Auto Reconnect (DO NOT USE SALHACK ONE)

• Enable Kill Aura on hostile mobs as you might run into magma slimes

• Enable Fast Interact - Break & Place

• Don't keep lots of echests in your inventory when initially starting the bot as it will mine them until it hits the threshold amount (default is 8 echests) even if you have no more free space. Just keep them all in shulkers

More tips will be added after people run into issues 😛

# Bot Commands

## Commands
The command is your prefix then nhwbuild along with your parameters.
To dig the +X highway with Impacts .b prefix you do:
`.b nhwbuild 1 0`

To pave:
`.b nhwbuild 1 0 true`

To stop:
`.b nhwstop`

To print out some status info (send this to me when reporting bugs):
`.b nhwstatus`

## Axes
+X = 1 0

+Z = 0 1

-X = -1 0

-Z = 0 -1

+X+Z = 1 1

-X+Z = -1 1

+X-Z = 1 -1

-X-Z = -1 -1




## How is it so fast?

Magic. (Hours of [Mankool's](https://github.com/mankool0) and CHB's enduring excruciating testing)
