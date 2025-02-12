package com.pathplanner.lib.commands;

import com.pathplanner.lib.controllers.PathFollowingController;
import com.pathplanner.lib.path.*;
import com.pathplanner.lib.pathfinding.Pathfinding;
import com.pathplanner.lib.util.GeometryUtil;
import com.pathplanner.lib.util.PPLibTelemetry;
import com.pathplanner.lib.util.PathPlannerLogging;
import com.pathplanner.lib.util.ReplanningConfig;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Subsystem;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Base pathfinding command */
public class PathfindingCommand extends Command {
  private final Timer timer = new Timer();
  private final PathPlannerPath targetPath;
  private Pose2d targetPose;
  private final GoalEndState goalEndState;
  private final PathConstraints constraints;
  private final Supplier<Pose2d> poseSupplier;
  private final Supplier<ChassisSpeeds> speedsSupplier;
  private final Consumer<ChassisSpeeds> output;
  private final PathFollowingController controller;
  private final double rotationDelayDistance;
  private final ReplanningConfig replanningConfig;

  private PathPlannerPath currentPath;
  private PathPlannerTrajectory currentTrajectory;
  private Pose2d startingPose;

  private double timeOffset = 0;

  /**
   * Constructs a new base pathfinding command that will generate a path towards the given path.
   *
   * @param targetPath the path to pathfind to
   * @param constraints the path constraints to use while pathfinding
   * @param poseSupplier a supplier for the robot's current pose
   * @param speedsSupplier a supplier for the robot's current robot relative speeds
   * @param outputRobotRelative a consumer for the output speeds (robot relative)
   * @param controller Path following controller that will be used to follow the path
   * @param rotationDelayDistance How far the robot should travel before attempting to rotate to the
   *     final rotation
   * @param replanningConfig Path replanning configuration
   * @param requirements the subsystems required by this command
   */
  public PathfindingCommand(
      PathPlannerPath targetPath,
      PathConstraints constraints,
      Supplier<Pose2d> poseSupplier,
      Supplier<ChassisSpeeds> speedsSupplier,
      Consumer<ChassisSpeeds> outputRobotRelative,
      PathFollowingController controller,
      double rotationDelayDistance,
      ReplanningConfig replanningConfig,
      Subsystem... requirements) {
    addRequirements(requirements);

    Pathfinding.ensureInitialized();

    Rotation2d targetRotation = new Rotation2d();
    for (PathPoint p : targetPath.getAllPathPoints()) {
      if (p.holonomicRotation != null) {
        targetRotation = p.holonomicRotation;
        break;
      }
    }

    this.targetPath = targetPath;
    this.targetPose = new Pose2d(this.targetPath.getPoint(0).position, targetRotation);
    this.goalEndState =
        new GoalEndState(
            this.targetPath.getGlobalConstraints().getMaxVelocityMps(), targetRotation);
    this.constraints = constraints;
    this.controller = controller;
    this.poseSupplier = poseSupplier;
    this.speedsSupplier = speedsSupplier;
    this.output = outputRobotRelative;
    this.rotationDelayDistance = rotationDelayDistance;
    this.replanningConfig = replanningConfig;
  }

  /**
   * Constructs a new base pathfinding command that will generate a path towards the given pose.
   *
   * @param targetPose the pose to pathfind to, the rotation component is only relevant for
   *     holonomic drive trains
   * @param constraints the path constraints to use while pathfinding
   * @param goalEndVel The goal end velocity when reaching the target pose
   * @param poseSupplier a supplier for the robot's current pose
   * @param speedsSupplier a supplier for the robot's current robot relative speeds
   * @param outputRobotRelative a consumer for the output speeds (robot relative)
   * @param controller Path following controller that will be used to follow the path
   * @param rotationDelayDistance How far the robot should travel before attempting to rotate to the
   *     final rotation
   * @param replanningConfig Path replanning configuration
   * @param requirements the subsystems required by this command
   */
  public PathfindingCommand(
      Pose2d targetPose,
      PathConstraints constraints,
      double goalEndVel,
      Supplier<Pose2d> poseSupplier,
      Supplier<ChassisSpeeds> speedsSupplier,
      Consumer<ChassisSpeeds> outputRobotRelative,
      PathFollowingController controller,
      double rotationDelayDistance,
      ReplanningConfig replanningConfig,
      Subsystem... requirements) {
    addRequirements(requirements);

    Pathfinding.ensureInitialized();

    this.targetPath = null;
    this.targetPose = targetPose;
    this.goalEndState = new GoalEndState(goalEndVel, targetPose.getRotation());
    this.constraints = constraints;
    this.controller = controller;
    this.poseSupplier = poseSupplier;
    this.speedsSupplier = speedsSupplier;
    this.output = outputRobotRelative;
    this.rotationDelayDistance = rotationDelayDistance;
    this.replanningConfig = replanningConfig;
  }

  @Override
  public void initialize() {
    currentTrajectory = null;
    timeOffset = 0;

    Pose2d currentPose = poseSupplier.get();

    controller.reset(currentPose, speedsSupplier.get());

    if (targetPath != null) {
      targetPose = new Pose2d(this.targetPath.getPoint(0).position, goalEndState.getRotation());
    }

    if (currentPose.getTranslation().getDistance(targetPose.getTranslation()) < 0.25) {
      this.cancel();
    } else {
      Pathfinding.setStartPosition(currentPose.getTranslation());
      Pathfinding.setGoalPosition(targetPose.getTranslation());
    }

    startingPose = currentPose;
  }

  @Override
  public void execute() {
    Pose2d currentPose = poseSupplier.get();
    ChassisSpeeds currentSpeeds = speedsSupplier.get();

    PathPlannerLogging.logCurrentPose(currentPose);
    PPLibTelemetry.setCurrentPose(currentPose);

    if (Pathfinding.isNewPathAvailable()) {
      currentPath = Pathfinding.getCurrentPath(constraints, goalEndState);

      if (currentPath != null) {
        ChassisSpeeds fieldRelativeSpeeds =
            ChassisSpeeds.fromRobotRelativeSpeeds(currentSpeeds, currentPose.getRotation());
        Rotation2d currentHeading =
            new Rotation2d(
                fieldRelativeSpeeds.vxMetersPerSecond, fieldRelativeSpeeds.vyMetersPerSecond);
        Rotation2d headingError =
            currentHeading.minus(currentPath.getStartingDifferentialPose().getRotation());
        boolean onHeading =
            Math.hypot(currentSpeeds.vxMetersPerSecond, currentSpeeds.vyMetersPerSecond) < 0.5
                || Math.abs(headingError.getDegrees()) < 30;

        if (!replanningConfig.enableInitialReplanning
            || (currentPose.getTranslation().getDistance(currentPath.getPoint(0).position) <= 0.25
                && onHeading)) {
          currentTrajectory = new PathPlannerTrajectory(currentPath, currentSpeeds);

          // Find the two closest states in front of and behind robot
          int closestState1Idx = 0;
          int closestState2Idx = 1;
          while (true) {
            double closest2Dist =
                currentTrajectory
                    .getState(closestState2Idx)
                    .positionMeters
                    .getDistance(currentPose.getTranslation());
            double nextDist =
                currentTrajectory
                    .getState(closestState2Idx + 1)
                    .positionMeters
                    .getDistance(currentPose.getTranslation());
            if (nextDist < closest2Dist) {
              closestState1Idx++;
              closestState2Idx++;
            } else {
              break;
            }
          }

          // Use the closest 2 states to interpolate what the time offset should be
          // This will account for the delay in pathfinding
          var closestState1 = currentTrajectory.getState(closestState1Idx);
          var closestState2 = currentTrajectory.getState(closestState2Idx);

          double d = closestState1.positionMeters.getDistance(closestState2.positionMeters);
          double t = (currentPose.getTranslation().getDistance(closestState1.positionMeters)) / d;

          timeOffset =
              GeometryUtil.doubleLerp(closestState1.timeSeconds, closestState2.timeSeconds, t);

          PathPlannerLogging.logActivePath(currentPath);
          PPLibTelemetry.setCurrentPath(currentPath);
        } else {
          PathPlannerPath replanned = currentPath.replan(currentPose, currentSpeeds);
          currentTrajectory = new PathPlannerTrajectory(replanned, currentSpeeds);

          timeOffset = 0;

          PathPlannerLogging.logActivePath(replanned);
          PPLibTelemetry.setCurrentPath(replanned);
        }

        timer.reset();
        timer.start();
      }
    }

    if (currentTrajectory != null) {
      PathPlannerTrajectory.State targetState = currentTrajectory.sample(timer.get() + timeOffset);

      if (replanningConfig.enableDynamicReplanning) {
        double previousError = Math.abs(controller.getPositionalError());
        double currentError = currentPose.getTranslation().getDistance(targetState.positionMeters);

        if (currentError >= replanningConfig.dynamicReplanningTotalErrorThreshold
            || currentError - previousError
                >= replanningConfig.dynamicReplanningErrorSpikeThreshold) {
          replanPath(currentPose, currentSpeeds);
          timer.reset();
          targetState = currentTrajectory.sample(0);
        }
      }

      // Set the target rotation to the starting rotation if we have not yet traveled the rotation
      // delay distance
      if (currentPose.getTranslation().getDistance(startingPose.getTranslation())
          < rotationDelayDistance) {
        targetState.targetHolonomicRotation = startingPose.getRotation();
      }

      ChassisSpeeds targetSpeeds =
          controller.calculateRobotRelativeSpeeds(currentPose, targetState);

      double currentVel =
          Math.hypot(currentSpeeds.vxMetersPerSecond, currentSpeeds.vyMetersPerSecond);

      PPLibTelemetry.setCurrentPose(currentPose);
      PathPlannerLogging.logCurrentPose(currentPose);

      if (controller.isHolonomic()) {
        PPLibTelemetry.setTargetPose(targetState.getTargetHolonomicPose());
        PathPlannerLogging.logTargetPose(targetState.getTargetHolonomicPose());
      } else {
        PPLibTelemetry.setTargetPose(targetState.getDifferentialPose());
        PathPlannerLogging.logTargetPose(targetState.getDifferentialPose());
      }

      PPLibTelemetry.setVelocities(
          currentVel,
          targetState.velocityMps,
          currentSpeeds.omegaRadiansPerSecond,
          targetSpeeds.omegaRadiansPerSecond);
      PPLibTelemetry.setPathInaccuracy(controller.getPositionalError());

      output.accept(targetSpeeds);
    }
  }

  @Override
  public boolean isFinished() {
    if (targetPath != null) {
      Pose2d currentPose = poseSupplier.get();
      ChassisSpeeds currentSpeeds = speedsSupplier.get();

      double currentVel =
          Math.hypot(currentSpeeds.vxMetersPerSecond, currentSpeeds.vyMetersPerSecond);
      double stoppingDistance =
          Math.pow(currentVel, 2) / (2 * constraints.getMaxAccelerationMpsSq());

      return currentPose.getTranslation().getDistance(targetPath.getPoint(0).position)
          <= stoppingDistance;
    }

    if (currentTrajectory != null) {
      return timer.hasElapsed(currentTrajectory.getTotalTimeSeconds() - timeOffset);
    }

    return false;
  }

  @Override
  public void end(boolean interrupted) {
    timer.stop();

    // Only output 0 speeds when ending a path that is supposed to stop, this allows interrupting
    // the command to smoothly transition into some auto-alignment routine
    if (!interrupted && goalEndState.getVelocity() < 0.1) {
      output.accept(new ChassisSpeeds());
    }
  }

  private void replanPath(Pose2d currentPose, ChassisSpeeds currentSpeeds) {
    PathPlannerPath replanned = currentPath.replan(currentPose, currentSpeeds);
    currentTrajectory = new PathPlannerTrajectory(replanned, currentSpeeds);
    PathPlannerLogging.logActivePath(replanned);
    PPLibTelemetry.setCurrentPath(replanned);
  }
}
