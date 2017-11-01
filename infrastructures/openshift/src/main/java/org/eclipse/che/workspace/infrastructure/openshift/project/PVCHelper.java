package org.eclipse.che.workspace.infrastructure.openshift.project;

import static java.util.Collections.singletonMap;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSource;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;
import org.eclipse.che.api.workspace.server.spi.InternalInfrastructureException;
import org.eclipse.che.workspace.infrastructure.openshift.OpenShiftClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** @author Anton Korneta */
public class PVCHelper {

  private static final Logger LOG = LoggerFactory.getLogger(PVCHelper.class);

  private static final String POD_PHASE_SUCCEEDED = "Succeeded";
  private static final String POD_PHASE_FAILED = "Failed";

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
    try {
      createJobPod(openShiftProject, workspaceId);
    } catch (IOException e) {
      throw new InternalInfrastructureException("Failed to cleanup workspace data folder");
    }
  }

  protected boolean createJobPod(String projectNamespace, String workspaceId) throws IOException {
    final List<String> allDirs = new ArrayList<>();
    allDirs.add(workspaceId + projectFolderPath);
    final String[] allDirsArray = allDirs.toArray(new String[allDirs.size()]);

    final VolumeMount vm =
        new VolumeMountBuilder().withMountPath(projectFolderPath).withName(pvcName).build();
    final PersistentVolumeClaimVolumeSource pvcs =
        new PersistentVolumeClaimVolumeSourceBuilder().withClaimName(pvcName).build();
    final Volume volume =
        new VolumeBuilder().withPersistentVolumeClaim(pvcs).withName(pvcName).build();

    final String[] jobCommand = getCommand(projectFolderPath, allDirsArray);
    LOG.info("Executing command {} in PVC {} for {} dirs", jobCommand[0], pvcName, allDirs.size());
    final String podName = "pvc_cleaner_pod_" + workspaceId;

    final Container container =
        new ContainerBuilder()
            .withName(podName)
            .withImage(jobImage)
            .withImagePullPolicy("IfNotPresent")
            .withNewSecurityContext()
            .withPrivileged(false)
            .endSecurityContext()
            .withCommand(jobCommand)
            .withVolumeMounts(vm)
            .withNewResources()
            .withLimits(singletonMap("memory", new Quantity(jobMemoryLimit)))
            .endResources()
            .build();
    final Pod podSpec =
        new PodBuilder()
            .withNewMetadata()
            .withName(podName)
            .endMetadata()
            .withNewSpec()
            .withContainers(container)
            .withVolumes(volume)
            .withRestartPolicy("Never")
            .endSpec()
            .build();

    try (OpenShiftClient openShiftClient = clientFactory.create()) {
      openShiftClient.pods().inNamespace(projectNamespace).create(podSpec);
      while (true) {
        Pod pod = openShiftClient.pods().inNamespace(projectNamespace).withName(podName).get();
        String phase = pod.getStatus().getPhase();
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

  private String[] getCommand(String mountPath, String... dirs) {
    String[] command = RM_DIR_WORKSPACE_COMMAND;
    String[] dirsWithPath = Arrays.stream(dirs).map(dir -> mountPath + dir).toArray(String[]::new);

    String[] fullCommand = new String[command.length + dirsWithPath.length];

    System.arraycopy(command, 0, fullCommand, 0, command.length);
    System.arraycopy(dirsWithPath, 0, fullCommand, command.length, dirsWithPath.length);
    return fullCommand;
  }
}
