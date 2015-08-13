/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2015 ForgeRock AS.
 */

package org.forgerock.openam.rest.uma;

import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.object;
import static org.forgerock.json.resource.ResourceException.newNotSupportedException;
import static org.forgerock.json.resource.Responses.*;
import static org.forgerock.util.promise.Promises.newExceptionPromise;
import static org.forgerock.util.promise.Promises.newResultPromise;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.forgerock.http.context.ServerContext;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.ActionResponse;
import org.forgerock.json.resource.CollectionResourceProvider;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.json.resource.DeleteRequest;
import org.forgerock.json.resource.PatchRequest;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResourceHandler;
import org.forgerock.json.resource.QueryResponse;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.openam.forgerockrest.entitlements.query.QueryResultHandlerBuilder;
import org.forgerock.openam.forgerockrest.utils.JsonValueQueryFilterVisitor;
import org.forgerock.openam.forgerockrest.utils.ServerContextUtils;
import org.forgerock.openam.rest.resource.ContextHelper;
import org.forgerock.openam.sm.datalayer.impl.uma.UmaPendingRequest;
import org.forgerock.openam.uma.PendingRequestsService;
import org.forgerock.util.AsyncFunction;
import org.forgerock.util.promise.Promise;
import org.forgerock.util.promise.Promises;

/**
 * CREST resource for UMA Pending Requests.
 *
 * @since 13.0.0
 */
public class PendingRequestResource implements CollectionResourceProvider {

    private static final String APPROVE_ACTION_ID = "approve";
    private static final String DENY_ACTION_ID = "deny";
    private static final JsonValueQueryFilterVisitor QUERY_VISITOR = new JsonValueQueryFilterVisitor();

    private final PendingRequestsService service;
    private final ContextHelper contextHelper;

    @Inject
    public PendingRequestResource(PendingRequestsService service,
            ContextHelper contextHelper) {
        this.service = service;
        this.contextHelper = contextHelper;
    }

    @Override
    public Promise<ActionResponse, ResourceException> actionCollection(ServerContext context, ActionRequest request) {
        try {
            if (APPROVE_ACTION_ID.equalsIgnoreCase(request.getAction())) {
                List<Promise<Void, ResourceException>> promises = new ArrayList<>();
                JsonValue content = request.getContent();
                for (UmaPendingRequest pendingRequest : queryResourceOwnerPendingRequests(context)) {
                    promises.add(service.approvePendingRequest(context, pendingRequest.getId(),
                            content.get(pendingRequest.getId()), ServerContextUtils.getRealm(context)));
                }
                return handlePendingRequestApproval(promises);
            } else if (DENY_ACTION_ID.equalsIgnoreCase(request.getAction())) {
                for (UmaPendingRequest pendingRequest : queryResourceOwnerPendingRequests(context)) {
                    service.denyPendingRequest(pendingRequest.getId(), ServerContextUtils.getRealm(context));
                }
                return newResultPromise(newActionResponse((json(object()))));
            } else {
                return newExceptionPromise(newNotSupportedException("Action, " + request.getAction()
                        + ", is not supported."));
            }
        } catch (ResourceException e) {
            return newExceptionPromise(e);
        }
    }

    @Override
    public Promise<ActionResponse, ResourceException> actionInstance(ServerContext context, String resourceId,
            ActionRequest request) {
        try {
            if (APPROVE_ACTION_ID.equalsIgnoreCase(request.getAction())) {
                return handlePendingRequestApproval(service.approvePendingRequest(context, resourceId, request.getContent(),
                                ServerContextUtils.getRealm(context)));
            } else if (DENY_ACTION_ID.equalsIgnoreCase(request.getAction())) {
                service.denyPendingRequest(resourceId, ServerContextUtils.getRealm(context));
                return newResultPromise(newActionResponse(json(object())));
            } else {
                return newExceptionPromise(newNotSupportedException("Action, " + request.getAction() + ", is not supported."));
            }
        } catch (ResourceException e) {
            return newExceptionPromise(e);
        }
    }

    private Promise<ActionResponse, ResourceException> handlePendingRequestApproval(Promise<Void, ResourceException> promise) {
        return handlePendingRequestApproval(Collections.singletonList(promise));
    }

    private Promise<ActionResponse, ResourceException> handlePendingRequestApproval(List<Promise<Void, ResourceException>> promises) {
        return Promises.when(promises)
                .thenAsync(new AsyncFunction<List<Void>, ActionResponse, ResourceException>() {
                    @Override
                    public Promise<ActionResponse, ResourceException> apply(List<Void> value) throws ResourceException {
                        return newResultPromise(newActionResponse(json(object())));
                    }
                });
    }

    @Override
    public Promise<QueryResponse, ResourceException> queryCollection(ServerContext context, QueryRequest request,
            QueryResourceHandler handler) {
        if (request.getQueryFilter() == null) {
            return newExceptionPromise(newNotSupportedException("Only query filter is supported."));
        }

        handler = QueryResultHandlerBuilder.withPagingAndSorting(handler, request);

        try {
            for (UmaPendingRequest pendingRequest : queryResourceOwnerPendingRequests(context)) {
                if (request.getQueryFilter().accept(QUERY_VISITOR, pendingRequest.asJson())) {
                    handler.handleResource(newResource(pendingRequest));
                }
            }
            return newResultPromise(newQueryResponse());
        } catch (ResourceException e) {
            return newExceptionPromise(e);
        }
    }

    @Override
    public Promise<ResourceResponse, ResourceException> readInstance(ServerContext context, String resourceId,
            ReadRequest request) {
        try {
            return newResultPromise(newResource(service.readPendingRequest(resourceId)));
        } catch (ResourceException e) {
            return newExceptionPromise(e);
        }
    }

    private Set<UmaPendingRequest> queryResourceOwnerPendingRequests(ServerContext context) throws ResourceException {
        return service.queryPendingRequests(contextHelper.getUserId(context), ServerContextUtils.getRealm(context));
    }

    private ResourceResponse newResource(UmaPendingRequest request) {
        return newResourceResponse(request.getId(), String.valueOf(request.hashCode()), request.asJson());
    }

    @Override
    public Promise<ResourceResponse, ResourceException> createInstance(ServerContext context, CreateRequest request) {
        return newExceptionPromise(newNotSupportedException());
    }

    @Override
    public Promise<ResourceResponse, ResourceException> deleteInstance(ServerContext context, String resourceId,
            DeleteRequest request) {
        return newExceptionPromise(newNotSupportedException());
    }

    @Override
    public Promise<ResourceResponse, ResourceException> patchInstance(ServerContext context, String resourceId,
            PatchRequest request) {
        return newExceptionPromise(newNotSupportedException());
    }

    @Override
    public Promise<ResourceResponse, ResourceException> updateInstance(ServerContext context, String resourceId,
            UpdateRequest request) {
        return newExceptionPromise(newNotSupportedException());
    }
}
