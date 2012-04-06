/**
 * Copyright 2012 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HServerAddress;

public class AssignmentVerificationReport {
  protected static final Log LOG = LogFactory.getLog(
      AssignmentVerificationReport.class.getName());

  private String tableName = null;
  private boolean enforceLocality = false;
  private boolean isFilledUp = false;

  private int totalRegions = 0;
  private int totalRegionServers = 0;
  // for unassigned regions
  private List<HRegionInfo> unAssignedRegionsList =
    new ArrayList<HRegionInfo>();

  // For regions without valid favored nodes
  private List<HRegionInfo> regionsWithoutValidFavoredNodes =
    new ArrayList<HRegionInfo>();

  // For regions not running on the favored nodes
  private List<HRegionInfo> nonFavoredAssignedRegionList =
    new ArrayList<HRegionInfo>();

  // For regions running on the favored nodes
  private int totalFavoredAssignments = 0;
  private int[] favoredNodes = new int[HConstants.FAVORED_NODES_NUM];
  private float[] favoredNodesLocalitySummary = new float[HConstants.FAVORED_NODES_NUM];
  private float actualLocalitySummary = 0;

  // For region balancing information
  private int avgRegionsOnRS = 0;
  private int maxRegionsOnRS = 0;
  private int minRegionsOnRS = Integer.MAX_VALUE;
  private Set<HServerAddress> mostLoadedRSSet =
    new HashSet<HServerAddress>();
  private Set<HServerAddress> leastLoadedRSSet =
    new HashSet<HServerAddress>();

  public void fillUp(String tableName, RegionAssignmentSnapshot snapshot,
      Map<String, Map<String, Float>> regionLocalityMap) {
    // Set the table name
    this.tableName = tableName;

    // Get all the regions for this table
    List<HRegionInfo> regionInfoList =
      snapshot.getTableToRegionMap().get(tableName);
    // Get the total region num for the current table
    this.totalRegions = regionInfoList.size();

    // Get the existing assignment plan
    AssignmentPlan plan = snapshot.getExistingAssignmentPlan();
    // Get the region to region server mapping
    Map<HRegionInfo, HServerAddress> currentAssignment =
      snapshot.getRegionToRegionServerMap();
    // Initialize the server to its hosing region counter map
    Map<HServerAddress, Integer> serverToHostingRegionCounterMap =
      new HashMap<HServerAddress, Integer>();


    // Check the favored nodes and its locality information
    // Also keep tracker of the most loaded and least loaded region servers
    for (HRegionInfo region : regionInfoList) {
      try {
        HServerAddress currentRS = currentAssignment.get(region);
        // Handle unassigned regions
        if (currentRS == null) {
          unAssignedRegionsList.add(region);
          continue;
        }

        // Keep updating the server to is hosting region counter map
        Integer hostRegionCounter = serverToHostingRegionCounterMap.get(currentRS);
        if (hostRegionCounter == null) {
          hostRegionCounter = new Integer(0);
        }
        hostRegionCounter = hostRegionCounter.intValue() + 1;
        serverToHostingRegionCounterMap.put(currentRS, hostRegionCounter);

        // Get the favored nodes from the assignment plan and verify it.
        List<HServerAddress> favoredNodes = plan.getAssignment(region);
        if (favoredNodes == null ||
            favoredNodes.size() != HConstants.FAVORED_NODES_NUM) {
          regionsWithoutValidFavoredNodes.add(region);
          continue;
        }

        // Get the position of the current region server in the favored nodes list
        AssignmentPlan.POSITION favoredNodePosition =
          AssignmentPlan.getFavoredServerPosition(favoredNodes, currentRS);

        // Handle the non favored assignment.
        if (favoredNodePosition == null) {
          nonFavoredAssignedRegionList.add(region);
          continue;
        }
        // Increase the favored nodes assignment.
        this.favoredNodes[favoredNodePosition.ordinal()]++;
        totalFavoredAssignments++;

        // Summary the locality information for each favored nodes
        if (regionLocalityMap != null) {
          // Set the enforce locality as true;
          this.enforceLocality = true;

          // Get the region degree locality map
          Map<String, Float> regionDegreeLocalityMap =
            regionLocalityMap.get(region.getEncodedName());
          if (regionDegreeLocalityMap == null) {
            continue; // ignore the region which doesn't have any store files.
          }

          // Get the locality summary for each favored nodes
          for (AssignmentPlan.POSITION p : AssignmentPlan.POSITION.values()) {
            HServerAddress favoredNode = favoredNodes.get(p.ordinal());
            // Get the locality for the current favored nodes
            Float locality =
              regionDegreeLocalityMap.get(favoredNode.getHostname());
            if (locality != null) {
              this.favoredNodesLocalitySummary[p.ordinal()] += locality;
            }
          }


          // Get the locality summary for the current region server
          Float actualLocality =
            regionDegreeLocalityMap.get(currentRS.getHostname());
          if (actualLocality != null) {
            this.actualLocalitySummary += actualLocality;
          }
        }
      } catch (Exception e) {
        LOG.error("Cannot verify the region assignment for region " +
            ((region == null) ? " null " : region.getRegionNameAsString()) +
            "becuase of " + e);
      }
    }

    // Fill up the most loaded and least loaded region server information
    for (Map.Entry<HServerAddress, Integer> entry :
      serverToHostingRegionCounterMap.entrySet()) {
      HServerAddress currentRS = entry.getKey();
      int hostRegionCounter = entry.getValue().intValue();

      // Update the most loaded region server list and maxRegionsOnRS
      if (hostRegionCounter > this.maxRegionsOnRS) {
        maxRegionsOnRS = hostRegionCounter;
        this.mostLoadedRSSet.clear();
        this.mostLoadedRSSet.add(currentRS);
      } else if (hostRegionCounter == this.maxRegionsOnRS) {
        this.mostLoadedRSSet.add(currentRS);
      }

      // Update the least loaded region server list and minRegionsOnRS
      if (hostRegionCounter < this.minRegionsOnRS) {
        this.minRegionsOnRS = hostRegionCounter;
        this.leastLoadedRSSet.clear();
        this.leastLoadedRSSet.add(currentRS);
      } else if (hostRegionCounter == this.minRegionsOnRS) {
        this.leastLoadedRSSet.add(currentRS);
      }
    }

    // and total region servers
    this.totalRegionServers = serverToHostingRegionCounterMap.keySet().size();
    this.avgRegionsOnRS = (totalRegionServers == 0) ? 0 :
      (totalRegions / totalRegionServers);

    // Set the isFilledUp as true
    isFilledUp = true;
  }

  public void print(boolean isDetailMode) {
    if (!isFilledUp) {
      System.err.println("[Error] Region assignment verfication report" +
          "hasn't been filled up");
    }
    DecimalFormat df = new java.text.DecimalFormat( "#.##");

    // Print some basic information
    System.out.println("Region Assignment Verification for Table: " + tableName +
        "\n\tTotal regions : " + totalRegions);

    // Print the number of regions on each kinds of the favored nodes
    System.out.println("\tTotal regions on favored nodes " +
        totalFavoredAssignments);
    for (AssignmentPlan.POSITION p : AssignmentPlan.POSITION.values()) {
      System.out.println("\t\tTotal regions on "+ p.toString() +
          " region servers: " + favoredNodes[p.ordinal()]);
    }
    // Print the number of regions in each kinds of invalid assignment
    System.out.println("\tTotal unassigned regions: " +
        unAssignedRegionsList.size());
    if (isDetailMode) {
      for (HRegionInfo region : unAssignedRegionsList) {
        System.out.println("\t\t" + region.getRegionNameAsString());
      }
    }

    System.out.println("\tTotal regions NOT on favored nodes: " +
        nonFavoredAssignedRegionList.size());
    if (isDetailMode) {
      for (HRegionInfo region : nonFavoredAssignedRegionList) {
        System.out.println("\t\t" + region.getRegionNameAsString());
      }
    }

    System.out.println("\tTotal regions without favored nodes: " +
        regionsWithoutValidFavoredNodes.size());
    if (isDetailMode) {
      for (HRegionInfo region : regionsWithoutValidFavoredNodes) {
        System.out.println("\t\t" + region.getRegionNameAsString());
      }
    }

    // Print the locality information if enabled
    if (this.enforceLocality && totalRegions != 0) {
      // Print the actual locality for this table
      float actualLocality = 100 *
        this.actualLocalitySummary / (float) totalRegions;
      System.out.println("\n\tThe actual avg locality is " +
          df.format(actualLocality) + " %");

      // Print the expected locality if regions are placed on the each kinds of
      // favored nodes
      for (AssignmentPlan.POSITION p : AssignmentPlan.POSITION.values()) {
        float avgLocality = 100 *
          (favoredNodesLocalitySummary[p.ordinal()] / (float) totalRegions);
        System.out.println("\t\tThe expected avg locality if all regions" +
			" on the " + p.toString() + " region servers: "
			+ df.format(avgLocality) + " %");
      }
    }

    // Print the region balancing information
    System.out.println("\n\tTotal hosting region servers: " +
        totalRegionServers);
    // Print the region balance information
    if (totalRegionServers != 0) {
      System.out.println("\tAvg regions/region server: " + avgRegionsOnRS +
          ";\tMax regions/region server: " + maxRegionsOnRS +
          ";\tMin regions/region server: " + minRegionsOnRS);

      // Print the details about the most loaded region servers
      System.out.println("\tThe number of the most loaded region servers: "
          + mostLoadedRSSet.size());
      if (isDetailMode) {
        int i = 0;
        for (HServerAddress addr : mostLoadedRSSet){
          if ((i++) % 3 == 0) {
            System.out.print("\n\t\t");
          }
          System.out.print(addr.getHostNameWithPort() + " ; ");
        }
        System.out.println("\n");
      }

      // Print the details about the least loaded region servers
      System.out.println("\tThe number of the least loaded region servers: "
          + leastLoadedRSSet.size());
      if (isDetailMode) {
        int i = 0;
        for (HServerAddress addr : leastLoadedRSSet){
          if ((i++) % 3 == 0) {
            System.out.print("\n\t\t");
          }
          System.out.print(addr.getHostNameWithPort() + " ; ");
        }
        System.out.println();
      }
    }
    System.out.println("==============================");
  }
}
