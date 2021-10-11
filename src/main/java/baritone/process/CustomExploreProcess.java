/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.ICustomExploreProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.world.chunk.Chunk;

import java.awt.geom.Point2D;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class CustomExploreProcess extends BaritoneProcessHelper implements ICustomExploreProcess {

    private final HashSet<BetterBlockPos> gotoQueue = new LinkedHashSet<>();
    private final HashSet<BetterBlockPos> finishedChunks = new LinkedHashSet<>();
    private final List<BetterBlockPos> tempFinishedChunks = new ArrayList<>();
    private GoalXZ curGoal;
    private boolean ready = false;
    private final int defaultY = 64;
    private int finishedChunksCount = 0;
    private int totalChunks = 0;
    private final String fileName = "finishedChunks.txt";
    private int dimension = 0;

    public CustomExploreProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        return ready;
    }

    public void explore(int pointsDist, int startX, int startZ, int endX, int endZ) {
        gotoQueue.clear();
        finishedChunks.clear();

        try {
            File myFile = new File(fileName);
            if (myFile.createNewFile()){
                logDirect("Created file to save finished chunks");
            }
        } catch (IOException e) {
            logDirect("ERROR CREATING FILE TO SAVE FINISHED CHUNKS");
            return;
        }


        int cStartX = startX >> 4;
        int cStartZ = startZ >> 4;
        int cEndX = endX >> 4;
        int cEndZ = endZ >> 4;

        int centerStartX = (cStartX << 4) + 8;
        int centerStartZ = (cStartZ << 4) + 8;

        int centerEndX = (cEndX << 4) + 8;
        int centerEndZ = (cEndZ << 4) + 8;

        BetterBlockPos startPos = new BetterBlockPos(centerStartX, defaultY, centerStartZ);
        BetterBlockPos endPos = new BetterBlockPos(centerEndX, defaultY, centerEndZ);
        generateWaypoints(startPos, endPos, pointsDist);
    }

    public void generateWaypoints(BetterBlockPos start, BetterBlockPos end, int wayPointDist) {
        int curX;
        int curZ;
        if (start.getX() <= end.getX() && start.getZ() <= end.getZ()) {
            // going left to right and bottom to top
            for (curX = start.getX(); curX <= end.getX();) {
                // do the bottom to top
                for (curZ = start.getZ(); curZ <= end.getZ();) {
                    gotoQueue.add(new BetterBlockPos(curX, defaultY, curZ));
                    curZ += wayPointDist;
                }

                curX += wayPointDist; // move one over to the right

                // do the top to bottom
                for (curZ = end.getZ(); curZ >= start.getZ();) {
                    gotoQueue.add(new BetterBlockPos(curX, defaultY, curZ));
                    curZ -= wayPointDist;
                }

                curX += wayPointDist; // move one over to the right
            }
        }
        else if (start.getX() <= end.getX() && start.getZ() > end.getZ()) {
            //going left to right and top to bottom
            for (curX = start.getX(); curX <= end.getX();) {

                // do top to bottom
                for (curZ = start.getZ(); curZ >= end.getZ();) {
                    gotoQueue.add(new BetterBlockPos(curX, defaultY, curZ));
                    curZ -= wayPointDist;
                }

                curX += wayPointDist; // move one over to the right

                // do bottom to top
                for (curZ = end.getZ(); curZ <= start.getZ();) {
                    gotoQueue.add(new BetterBlockPos(curX, defaultY, curZ));
                    curZ += wayPointDist;
                }

                curX += wayPointDist; // move one over to the right
            }
        }
        else if (start.getX() > end.getX() && start.getZ() <= end.getZ()) {
            //going right to left and bottom to top
            for (curX = start.getX(); curX > end.getX();) {
                // do the bottom to top
                for (curZ = start.getZ(); curZ <= end.getZ();) {
                    gotoQueue.add(new BetterBlockPos(curX, defaultY, curZ));
                    curZ += wayPointDist;
                }

                curX -= wayPointDist; // move one over to the left

                // do the top to bottom
                for (curZ = end.getZ(); curZ >= start.getZ();) {
                    gotoQueue.add(new BetterBlockPos(curX, defaultY, curZ));
                    curZ -= wayPointDist;
                }

                curX -= wayPointDist; // move one over to the left
            }
        }
        else if (start.getX() > end.getX() && start.getZ() > end.getZ()) {
            //going right to left and top to bottom
            for (curX = start.getX(); curX > end.getX();) {
                // do top to bottom
                for (curZ = start.getZ(); curZ >= end.getZ();) {
                    gotoQueue.add(new BetterBlockPos(curX, defaultY, curZ));
                    curZ -= wayPointDist;
                }

                curX -= wayPointDist; // move one over to the left

                // do bottom to top
                for (curZ = end.getZ(); curZ <= start.getZ();) {
                    gotoQueue.add(new BetterBlockPos(curX, defaultY, curZ));
                    curZ += wayPointDist;
                }

                curX -= wayPointDist; // move one over to the left
            }
        }



        logDirect(String.format("Generated %d waypoints", gotoQueue.size()));
        finishedChunksCount = 0;
        totalChunks = gotoQueue.size();

        try {
            FileReader fileReader = new FileReader(fileName);
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            String line;
            while((line = bufferedReader.readLine()) != null) {
                processFinishedChunkLine(line);
            }
        } catch (Exception e) {
            logDirect(e.toString());
            logDirect("Error starting :(");
            return;
        }


        logDirect("Explored " + finishedChunksCount + "/" + totalChunks + " chunks");
        this.dimension = mc.player.dimension;
        ready = true;
    }

    private void processFinishedChunkLine(String line) {
        String[] chunk = line.split(",");
        int xChunk = Integer.parseInt(chunk[0]);
        int zChunk = Integer.parseInt(chunk[1]);
        finishedChunks.add(new BetterBlockPos(xChunk, defaultY, zChunk));
        if(gotoQueue.remove(new BetterBlockPos((xChunk << 4) + 8, defaultY, (zChunk << 4) + 8))) {
            finishedChunksCount++;
        }
    }

    private int removeExplored() {
        tempFinishedChunks.clear();

        int removed = 0;
        Iterator<BetterBlockPos> i = gotoQueue.iterator();
        while (i.hasNext()) {
            BetterBlockPos pos = i.next();
            int chunkX = pos.x >> 4;
            int chunkZ = pos.z >> 4;
            Chunk ourChunk = ctx.world().getChunk(chunkX, chunkZ);
            if (ourChunk.isLoaded() && !ourChunk.isEmpty()) {
                i.remove();
                removed++;
            }
        }


        StringBuilder toWrite = new StringBuilder();
        for (BetterBlockPos pos : tempFinishedChunks) {
            toWrite.append(pos.x).append(",").append(pos.z).append("\n");
        }
        try {
            Files.write(Paths.get(fileName), toWrite.toString().getBytes(), StandardOpenOption.APPEND);
        }catch (IOException e) {
            logDirect("ERROR SAVING INFO BELOW SAVE MANUALLY!!!");
            logDirect(toWrite.toString());
        }

        return removed;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!ready) {
            return null;
        }
        if (gotoQueue.isEmpty()) {
            ready = false;
            logDirect("Finished");
            onLostControl();
            return null;
        }

        // Crash upon dimension no longer being the starting dimension
        if (mc.player.dimension != dimension) {
            //mc.world.sendQuittingDisconnectingPacket();
            logDirect("Exited the starting dimension, shutting down.");
            mc.shutdown();
        }


        if (curGoal != null && Point2D.distance(curGoal.getX(), curGoal.getZ(), baritone.getPlayerContext().playerFeet().x, baritone.getPlayerContext().playerFeet().z) <= 20) {
            finishedChunksCount += removeExplored();
            logDirect("Explored " + finishedChunksCount + "/" + totalChunks + " chunks");
            curGoal = null; // No goal since we have arrived
            return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH); // Go back and recheck if we're done
        }

        if (curGoal != null) {
            return new PathingCommand(curGoal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
        }

        BetterBlockPos closestPos = gotoQueue.iterator().next();

        // If distance is too far from next pos then we get the closest one
        if (Point2D.distance(closestPos.x, closestPos.z, baritone.getPlayerContext().playerFeet().x, baritone.getPlayerContext().playerFeet().z) >= 400) {
            double closestDist = Double.MAX_VALUE;
            for (BetterBlockPos pos : gotoQueue) {
                double tempDist = Point2D.distance(pos.x, pos.z, baritone.getPlayerContext().playerFeet().x, baritone.getPlayerContext().playerFeet().z);
                if (tempDist < closestDist) {
                    closestDist = tempDist;
                    closestPos = pos;
                }
            }
        }

        curGoal = new GoalXZ(closestPos);
        return new PathingCommand(curGoal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        ready = false;
        curGoal = null;
    }

    @Override
    public String displayName0() {
        return "Explored " + finishedChunksCount + "/" + totalChunks + " chunks\n" + "Current Goal " + curGoal;
    }

}
