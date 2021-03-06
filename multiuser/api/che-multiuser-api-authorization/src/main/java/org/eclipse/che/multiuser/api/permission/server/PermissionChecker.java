/*
 * Copyright (c) 2012-2017 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.multiuser.api.permission.server;

import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;

/**
 * Checks user's permission to perform some action with particular instance of given domain.
 *
 * @author Sergii Leschenko
 */
public interface PermissionChecker {
  /**
   * Checks user's permission to perform some action with particular instance.
   *
   * @param user user id
   * @param domain domain id
   * @param instance instance id
   * @param action action name
   * @return true if the user has given permission
   * @throws NotFoundException when given domain is unsupported
   * @throws ConflictException when given domain requires non nullable value for instance but it is
   *     null
   * @throws ServerException when any other error occurs during permission existence checking
   */
  boolean hasPermission(String user, String domain, String instance, String action)
      throws ServerException, NotFoundException, ConflictException;
}
