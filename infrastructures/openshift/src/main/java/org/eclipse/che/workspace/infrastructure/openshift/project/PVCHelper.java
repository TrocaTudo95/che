package org.eclipse.che.workspace.infrastructure.openshift.project;

import static java.lang.System.arraycopy;
import static java.util.Collections.singletonMap;
import static org.eclipse.che.workspace.infrastructure.openshift.project.OpenShiftObjectUtil.newVolume;
import static org.eclipse.che.workspace.infrastructure.openshift.project.OpenShiftObjectUtil.newVolumeMount;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.openshift.client.OpenShiftClient;
import java.util.Arrays;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.eclipse.che.api.workspace.server.spi.InternalInfrastructureException;
import org.eclipse.che.workspace.infrastructure.openshift.OpenShiftClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for executing simple commands in a Persistent Volume on Openshift.
 *
 * <p>Creates a short-lived Pod using a CentOS image which mounts a specified PVC and executes a
 * command (either {@code mkdir -p <path>} or {@code rm -rf <path}). Reports back whether the pod
 * succeeded or failed. Supports multiple paths for one command.
 *
 * <p>For mkdir commands, an in-memory list of created workspaces is stored and used to avoid
 * calling mkdir unnecessarily. However, this list is not persisted, so dir creation is not tracked
 * between restarts.
 *
 * @author amisevsk
 * @author Anton Korneta
 */
@Singleton
public class PVCHelper {

  private static final Logger LOG = LoggerFactory.getLogger(PVCHelper.class);

  private Pattern t =
      Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*");

  private static final String POD_PHASE_SUCCEEDED = "Succeeded";
  private static final String POD_PHASE_FAILED = "Failed";
  private static final String PULL_POLICY = "IfNotPresent";
  private static final String RESTART_POLICY = "Never";

  private final String pvcName;
  private final String projectFolderPath;
  private final String jobImage;
  private final String jobMemoryLimit;
  private final OpenShiftClientFactory clientFactory;

  private static final String[] RM_DIR_WORKSPACE_COMMAND = new String[] {"rm", "-rf"};

  @Inject
  public PVCHelper(
      @Named("che.infra.openshift.pvc.name") String pvcName,
      @Named("che.workspace.projects.storage") String projectFolderPath,
      @Named("che.openshift.jobs.image") String jobImage,
      @Named("che.openshift.jobs.memorylimit") String jobMemoryLimit,
      OpenShiftClientFactory clientFactory) {
    this.pvcName = pvcName;
    this.projectFolderPath = projectFolderPath;
    this.jobImage = jobImage;
    this.jobMemoryLimit = jobMemoryLimit;
    this.clientFactory = clientFactory;
  }

  public void cleanup(String openShiftProject, String workspaceId)
      throws InternalInfrastructureException {
    createJobPod(openShiftProject, workspaceId);
  }

  private boolean createJobPod(String projectNamespace, String workspaceId) {
    final String[] jobCommand = getCommand(workspaceId);
    LOG.info("Executing command {} in PVC {} for {} dirs", jobCommand[0], pvcName, workspaceId);
    final String cleanerName = "pvc-cleaner-" + workspaceId;
    final Container container =
        new ContainerBuilder()
            .withName(cleanerName)
            .withImage(jobImage)
            .withImagePullPolicy(PULL_POLICY)
            .withNewSecurityContext()
            .withPrivileged(false)
            .endSecurityContext()
            .withCommand(jobCommand)
            .withVolumeMounts(newVolumeMount(pvcName, projectFolderPath, null))
            .withNewResources()
            .withLimits(singletonMap("memory", new Quantity(jobMemoryLimit)))
            .endResources()
            .build();
    final Pod podSpec =
        new PodBuilder()
            .withNewMetadata()
            .withName(cleanerName)
            .endMetadata()
            .withNewSpec()
            .withContainers(container)
            .withVolumes(newVolume(pvcName, pvcName))
            .withRestartPolicy(RESTART_POLICY)
            .endSpec()
            .build();

    try (OpenShiftClient openShiftClient = clientFactory.create()) {
      openShiftClient.pods().inNamespace(projectNamespace).create(podSpec);
      while (true) {
        final Pod pod =
            openShiftClient.pods().inNamespace(projectNamespace).withName(cleanerName).get();
        final String phase = pod.getStatus().getPhase();
        switch (phase) {
          case POD_PHASE_FAILED:
            LOG.info("Pod command {} failed", Arrays.toString(jobCommand));
            // fall through
          case POD_PHASE_SUCCEEDED:
            openShiftClient.resource(pod).delete();
            return POD_PHASE_SUCCEEDED.equals(phase);
          default:
            Thread.sleep(1000);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return false;
  }

  private static String[] getCommand(String mountPath) {
    final String[] fullCommand = new String[RM_DIR_WORKSPACE_COMMAND.length + 1];
    arraycopy(RM_DIR_WORKSPACE_COMMAND, 0, fullCommand, 0, RM_DIR_WORKSPACE_COMMAND.length);
    arraycopy(new String[] {mountPath}, 0, fullCommand, RM_DIR_WORKSPACE_COMMAND.length, 1);
    return fullCommand;
  }
}
