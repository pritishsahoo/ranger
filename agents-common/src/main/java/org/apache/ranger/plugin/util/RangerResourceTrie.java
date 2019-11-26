/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.plugin.util;


import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.policyengine.RangerPluginContext;
import org.apache.ranger.plugin.policyresourcematcher.RangerPolicyResourceEvaluator;
import org.apache.ranger.plugin.resourcematcher.RangerAbstractResourceMatcher;
import org.apache.ranger.plugin.resourcematcher.RangerResourceMatcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class RangerResourceTrie<T extends RangerPolicyResourceEvaluator> {
    private static final Log LOG = LogFactory.getLog(RangerResourceTrie.class);
    private static final Log TRACE_LOG = RangerPerfTracer.getPerfLogger("resourcetrie.trace");
    private static final Log PERF_TRIE_INIT_LOG = RangerPerfTracer.getPerfLogger("resourcetrie.init");
    private static final Log PERF_TRIE_OP_LOG = RangerPerfTracer.getPerfLogger("resourcetrie.op");

    private static final String DEFAULT_WILDCARD_CHARS = "*?";
    private static final String TRIE_BUILDER_THREAD_COUNT = "ranger.policyengine.trie.builder.thread.count";

    private final RangerServiceDef.RangerResourceDef resourceDef;
    private final boolean optIgnoreCase;
    private final boolean optWildcard;
    private final String wildcardChars;
    private final TrieNode<T> root;
    private final boolean isOptimizedForRetrieval;

    public RangerResourceTrie(RangerServiceDef.RangerResourceDef resourceDef, List<T> evaluators) {
        this(resourceDef, evaluators, true, null);
    }

    public RangerResourceTrie(RangerServiceDef.RangerResourceDef resourceDef, List<T> evaluators, boolean isOptimizedForRetrieval, RangerPluginContext pluginContext) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("==> RangerResourceTrie(" + resourceDef.getName() + ", evaluatorCount=" + evaluators.size() + ", isOptimizedForRetrieval=" + isOptimizedForRetrieval + ")");
        }

        RangerPerfTracer perf = null;

        if(RangerPerfTracer.isPerfTraceEnabled(PERF_TRIE_INIT_LOG)) {
            perf = RangerPerfTracer.getPerfTracer(PERF_TRIE_INIT_LOG, "RangerResourceTrie.init(name=" + resourceDef.getName() + ")");
        }

        Configuration config             = pluginContext != null ? pluginContext.getConfig() : null;
        int           builderThreadCount = config != null ? config.getInt(TRIE_BUILDER_THREAD_COUNT, 1) : 1;

        if (builderThreadCount < 1) {
            builderThreadCount = 1;
        }

        if (TRACE_LOG.isTraceEnabled()) {
            TRACE_LOG.trace("builderThreadCount is set to [" + builderThreadCount + "]");
        }

        Map<String, String> matcherOptions = resourceDef.getMatcherOptions();

        boolean optReplaceTokens = RangerAbstractResourceMatcher.getOptionReplaceTokens(matcherOptions);

        String tokenReplaceSpecialChars = "";

        if(optReplaceTokens) {
            char delimiterStart  = RangerAbstractResourceMatcher.getOptionDelimiterStart(matcherOptions);
            char delimiterEnd    = RangerAbstractResourceMatcher.getOptionDelimiterEnd(matcherOptions);
            char delimiterEscape = RangerAbstractResourceMatcher.getOptionDelimiterEscape(matcherOptions);

            tokenReplaceSpecialChars += delimiterStart;
            tokenReplaceSpecialChars += delimiterEnd;
            tokenReplaceSpecialChars += delimiterEscape;
        }

        this.resourceDef   = resourceDef;
        this.optIgnoreCase = RangerAbstractResourceMatcher.getOptionIgnoreCase(matcherOptions);
        this.optWildcard   = RangerAbstractResourceMatcher.getOptionWildCard(matcherOptions);
        this.wildcardChars = optWildcard ? DEFAULT_WILDCARD_CHARS + tokenReplaceSpecialChars : "" + tokenReplaceSpecialChars;
        this.isOptimizedForRetrieval = isOptimizedForRetrieval;

        TrieNode<T> tmpRoot = buildTrie(resourceDef, evaluators, builderThreadCount);

        if (builderThreadCount > 1 && tmpRoot == null) { // if multi-threaded trie-creation failed, build using a single thread
            this.root = buildTrie(resourceDef, evaluators, 1);
        } else {
            this.root = tmpRoot;
        }

        wrapUpUpdate();

        RangerPerfTracer.logAlways(perf);

        if (PERF_TRIE_INIT_LOG.isDebugEnabled()) {
            PERF_TRIE_INIT_LOG.debug(toString());
        }

        if (TRACE_LOG.isTraceEnabled()) {
            StringBuilder sb = new StringBuilder();
            root.toString("", sb);
            TRACE_LOG.trace("Trie Dump from RangerResourceTrie.init(name=" + resourceDef.getName() + "):\n{" + sb.toString() + "}");
        }

        if(LOG.isDebugEnabled()) {
            LOG.debug("<== RangerResourceTrie(" + resourceDef.getName() + ", evaluatorCount=" + evaluators.size() + ", isOptimizedForRetrieval=" + isOptimizedForRetrieval + "): " + toString());
        }
    }

    public String getResourceName() {
        return resourceDef.getName();
    }

    public Set<T> getEvaluatorsForResource(Object resource) {
        if (resource instanceof String) {
            return getEvaluatorsForResource((String) resource);
        } else if (resource instanceof Collection) {
            if (CollectionUtils.isEmpty((Collection) resource)) {  // treat empty collection same as empty-string
                return getEvaluatorsForResource("");
            } else {
                @SuppressWarnings("unchecked")
                Collection<String> resources = (Collection<String>) resource;

                return getEvaluatorsForResources(resources);
            }
        }

        return null;
    }

    public void add(RangerPolicyResource resource, T evaluator) {

        RangerPerfTracer perf = null;

        if(RangerPerfTracer.isPerfTraceEnabled(PERF_TRIE_INIT_LOG)) {
            perf = RangerPerfTracer.getPerfTracer(PERF_TRIE_INIT_LOG, "RangerResourceTrie.add(name=" + resource + ")");
        }

        if (resource == null) {
            if (evaluator.isAncestorOf(resourceDef)) {
                root.addWildcardEvaluator(evaluator);
            }
        } else {
            if (resource.getIsExcludes()) {
                root.addWildcardEvaluator(evaluator);
            } else {
                if (CollectionUtils.isNotEmpty(resource.getValues())) {
                    for (String value : resource.getValues()) {
                        insert(root, value, resource.getIsRecursive(), evaluator);
                    }
                }
            }
        }

        RangerPerfTracer.logAlways(perf);
        if (TRACE_LOG.isTraceEnabled()) {
            StringBuilder sb = new StringBuilder();
            root.toString("", sb);
            TRACE_LOG.trace("Trie Dump from RangerResourceTrie.add(name=" + resource + "):\n{" + sb.toString() + "}");
        }
    }

    public void delete(RangerPolicyResource resource, T evaluator) {

        RangerPerfTracer perf = null;

        if(RangerPerfTracer.isPerfTraceEnabled(PERF_TRIE_INIT_LOG)) {
            perf = RangerPerfTracer.getPerfTracer(PERF_TRIE_INIT_LOG, "RangerResourceTrie.delete(name=" + resource + ")");
        }

        boolean isRemoved = false;
        if (resource.getIsExcludes()) {
            isRemoved = root.removeWildcardEvaluator(evaluator);
        }
        if (!isRemoved) {
            for (String value : resource.getValues()) {
                TrieNode<T> node = getNodeForResource(value);
                if (node != null) {
                    node.removeEvaluatorFromSubtree(evaluator);
                }
            }
        }

        RangerPerfTracer.logAlways(perf);
        if (TRACE_LOG.isTraceEnabled()) {
            StringBuilder sb = new StringBuilder();
            root.toString("", sb);
            TRACE_LOG.trace("Trie Dump from RangerResourceTrie.delete(name=" + resource + "):\n{" + sb.toString() + "}");
        }
    }

    public void wrapUpUpdate() {
        if (root != null) {
            root.wrapUpUpdate();
        }
    }

    public boolean compareSubtree(RangerResourceTrie<T> other) {

        final boolean ret;
        List<TrieNode<T>> mismatchedNodes = new ArrayList<>();

        if (this.root == null || other.root == null) {
            ret = this.root == other.root;
            if (!ret) {
                mismatchedNodes.add(this.root);
            }
        } else {
            ret = compareSubtree(this.root, other.root, mismatchedNodes);
        }
        return ret;
    }

    private boolean compareSubtree(TrieNode<T> me, TrieNode<T> other, List<TrieNode<T>> misMatched) {
        boolean ret = StringUtils.equals(me.getStr(), other.getStr());

        if (ret) {
            Map<Character, TrieNode<T>> myChildren = me.getChildren();
            Map<Character, TrieNode<T>> otherChildren = other.getChildren();

            ret = myChildren.size() == otherChildren.size() &&
                    compareLists(me.getEvaluators(), other.getEvaluators()) &&
                    compareLists(me.getWildcardEvaluators(), other.getWildcardEvaluators()) &&
                    myChildren.keySet().size() == otherChildren.keySet().size();
            if (ret) {
                // Check if subtrees match
                for (Map.Entry<Character, TrieNode<T>> entry : myChildren.entrySet()) {
                    Character c = entry.getKey();
                    TrieNode<T> myNode = entry.getValue();
                    TrieNode<T> otherNode = otherChildren.get(c);
                    ret = otherNode != null && compareSubtree(myNode, otherNode, misMatched);
                    if (!ret) {
                        break;
                    }
                }
            }
        }

        if (!ret) {
            misMatched.add(me);
        }

        return ret;
    }

    private boolean compareLists(Set<? extends RangerPolicyResourceEvaluator> me, Set<? extends RangerPolicyResourceEvaluator> other) {
        boolean ret;

        if (me == null || other == null) {
            ret = me == other;
        } else {
            ret = me.size() == other.size();

            if (ret) {
		        List<? extends RangerPolicyResourceEvaluator> meAsList = new ArrayList<>(me);
		        List<? extends RangerPolicyResourceEvaluator> otherAsList = new ArrayList<>(other);

                List<Long> myIds = new ArrayList<>();
                List<Long> otherIds = new ArrayList<>();
                for (RangerPolicyResourceEvaluator evaluator : meAsList) {
                    myIds.add(evaluator.getId());
                }
                for (RangerPolicyResourceEvaluator evaluator : otherAsList) {
                    otherIds.add(evaluator.getId());
                }

                ret = compareLongLists(myIds, otherIds);
            }
        }
        return ret;
    }

    private boolean compareLongLists(List<Long> me, List<Long> other) {
        return me.size() == CollectionUtils.intersection(me, other).size();
    }

    private TrieNode<T> copyTrieSubtree(final TrieNode<T> source, final TrieNode<T> parent) {
        if (TRACE_LOG.isTraceEnabled()) {
            StringBuilder sb = new StringBuilder();
            source.toString(sb);
            TRACE_LOG.trace("==> copyTrieSubtree(" + sb + ")");
        }
        TrieNode<T> dest = new TrieNode<>(source.str);
        dest.setParent(parent);

        synchronized (source.children) {
            dest.isSetup = source.isSetup;
            dest.isSharingParentWildcardEvaluators = source.isSharingParentWildcardEvaluators;

            if (source.isSharingParentWildcardEvaluators) {
                if (dest.getParent() != null) {
                    dest.wildcardEvaluators = dest.getParent().getWildcardEvaluators();
                } else {
                    dest.wildcardEvaluators = null;
                }
            } else {
                if (source.wildcardEvaluators != null) {
                    dest.wildcardEvaluators = new HashSet<>(source.wildcardEvaluators);
                } else {
                    dest.wildcardEvaluators = null;
                }
            }
            if (source.evaluators != null) {
                if (source.evaluators == source.wildcardEvaluators) {
                    dest.evaluators = dest.wildcardEvaluators;
                } else {
                    dest.evaluators = new HashSet<>(source.evaluators);
                }
            } else {
                dest.evaluators = null;
            }
        }

        Map<Character, TrieNode<T>> children = source.getChildren();
        for (Map.Entry<Character, TrieNode<T>> entry : children.entrySet()) {
            copyTrieSubtree(entry.getValue(), dest);
        }

        if (TRACE_LOG.isTraceEnabled()) {
            StringBuilder sourceAsString = new StringBuilder(), destAsString = new StringBuilder();
            source.toString(sourceAsString);
            dest.toString(destAsString);

            TRACE_LOG.trace("<== copyTrieSubtree(" + sourceAsString + ") : " + destAsString);
        }
        return dest;
    }

    public RangerResourceTrie(RangerResourceTrie<T> other) {
        RangerPerfTracer perf = null;

        if(RangerPerfTracer.isPerfTraceEnabled(PERF_TRIE_INIT_LOG)) {
            perf = RangerPerfTracer.getPerfTracer(PERF_TRIE_INIT_LOG, "RangerResourceTrie.copyTrie(name=" + other.resourceDef.getName() + ")");
        }

        this.resourceDef = other.resourceDef;
        this.optIgnoreCase = other.optIgnoreCase;
        this.optWildcard = other.optWildcard;
        this.wildcardChars = other.wildcardChars;
        this.isOptimizedForRetrieval = false;
        this.root = copyTrieSubtree(other.root, null);

        RangerPerfTracer.logAlways(perf);

        if (PERF_TRIE_INIT_LOG.isDebugEnabled()) {
            PERF_TRIE_INIT_LOG.debug(toString());
        }
        if (TRACE_LOG.isTraceEnabled()) {
            StringBuilder sb = new StringBuilder();
            root.toString("", sb);
            TRACE_LOG.trace("Trie Dump from RangerResourceTrie.copyTrie(name=" + other.resourceDef.getName() + "):\n{" + sb.toString() + "}");
        }
    }

    private TrieNode<T> buildTrie(RangerServiceDef.RangerResourceDef resourceDef, List<T> evaluators, int builderThreadCount) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("==> buildTrie(" + resourceDef.getName() + ", evaluatorCount=" + evaluators.size() + ", isMultiThreaded=" + (builderThreadCount > 1) + ")");
        }

        RangerPerfTracer perf = null;

        if(RangerPerfTracer.isPerfTraceEnabled(PERF_TRIE_INIT_LOG)) {
            perf = RangerPerfTracer.getPerfTracer(PERF_TRIE_INIT_LOG, "RangerResourceTrie.init(resourceDef=" + resourceDef.getName() + ")");
        }

        TrieNode<T>                           ret                 = new TrieNode<>(null);
        final boolean                         isMultiThreaded = builderThreadCount > 1;
        final List<ResourceTrieBuilderThread> builderThreads;
        final Map<Character, Integer>         builderThreadMap;
        final String                          resourceName = resourceDef.getName();
        int                                   lastUsedThreadIndex = 0;

        if (isMultiThreaded) {
            builderThreads = new ArrayList<>();
            for (int i = 0; i < builderThreadCount; i++) {
                ResourceTrieBuilderThread t = new ResourceTrieBuilderThread();
                t.setDaemon(true);
                builderThreads.add(t);
                t.start();
            }
            builderThreadMap = new HashMap<>();
        } else {
            builderThreads = null;
            builderThreadMap = null;
        }

        for (T evaluator : evaluators) {
            Map<String, RangerPolicyResource> policyResources = evaluator.getPolicyResource();
            RangerPolicyResource policyResource = policyResources != null ? policyResources.get(resourceName) : null;

            if (policyResource == null) {
                if (evaluator.isAncestorOf(resourceDef)) {
                    ret.addWildcardEvaluator(evaluator);
                }

                continue;
            }

            if (policyResource.getIsExcludes()) {
                ret.addWildcardEvaluator(evaluator);
            } else {
                RangerResourceMatcher resourceMatcher = evaluator.getResourceMatcher(resourceName);

                if (resourceMatcher != null && (resourceMatcher.isMatchAny())) {
                    ret.addWildcardEvaluator(evaluator);
                } else {
                    if (CollectionUtils.isNotEmpty(policyResource.getValues())) {
                        for (String resource : policyResource.getValues()) {
                            if (!isMultiThreaded) {
                                insert(ret, resource, policyResource.getIsRecursive(), evaluator);
                            } else {
                                try {
                                    lastUsedThreadIndex = insert(ret, resource, policyResource.getIsRecursive(), evaluator, builderThreadMap, builderThreads, lastUsedThreadIndex);
                                } catch (InterruptedException ex) {
                                    LOG.error("Failed to dispatch " + resource + " to " + builderThreads.get(lastUsedThreadIndex));
                                    LOG.error("Failing and retrying with one thread");

                                    ret = null;

                                    break;
                                }
                            }
                        }
                        if (ret == null) {
                            break;
                        }
                    }
                }
            }
        }
        if (ret != null) {
            if (isMultiThreaded) {

                for (ResourceTrieBuilderThread t : builderThreads) {
                    try {
                        // Send termination signal to each thread
                        t.add("", false, null);
                        // Wait for threads to finish work
                        t.join();
                        ret.getChildren().putAll(t.getSubtrees());
                    } catch (InterruptedException ex) {
                        LOG.error("BuilderThread " + t + " was interrupted:", ex);
                        LOG.error("Failing and retrying with one thread");

                        ret = null;

                        break;
                    }
                }
                cleanUpThreads(builderThreads);
            }
        }

        RangerPerfTracer.logAlways(perf);

        if(LOG.isDebugEnabled()) {
            LOG.debug("<== buildTrie(" + resourceDef.getName() + ", evaluatorCount=" + evaluators.size() + ", isMultiThreaded=" + isMultiThreaded + ") :" +  ret);
        }

        return ret;
    }

    private void cleanUpThreads(List<ResourceTrieBuilderThread> builderThreads) {
        if (CollectionUtils.isNotEmpty(builderThreads)) {
            for (ResourceTrieBuilderThread t : builderThreads) {
                try {
                    if (t.isAlive()) {
                        t.interrupt();
                        t.join();
                    }
                } catch (InterruptedException ex) {
                    LOG.error("Could not terminate thread " + t);
                }
            }
        }
    }

    private TrieData getTrieData() {
        TrieData ret = new TrieData();

        root.populateTrieData(ret);
        ret.maxDepth = getMaxDepth();

        return ret;
    }

    private int getMaxDepth() {
        return root.getMaxDepth();
    }

    private Character getLookupChar(char ch) {
        return optIgnoreCase ? Character.toLowerCase(ch) : ch;
    }

    private Character getLookupChar(String str, int index) {
        return getLookupChar(str.charAt(index));
    }

    private int insert(TrieNode<T> currentRoot, String resource, boolean isRecursive, T evaluator, Map<Character, Integer> builderThreadMap, List<ResourceTrieBuilderThread> builderThreads, int lastUsedThreadIndex) throws InterruptedException {
        int          ret    = lastUsedThreadIndex;
        final String prefix = getNonWildcardPrefix(resource);

        if (StringUtils.isNotEmpty(prefix)) {
            char    c     = getLookupChar(prefix.charAt(0));
            Integer index = builderThreadMap.get(c);

            if (index == null) {
                ret = index = (lastUsedThreadIndex + 1) % builderThreads.size();
                builderThreadMap.put(c, index);
            }

            builderThreads.get(index).add(resource, isRecursive, evaluator);
        } else {
            currentRoot.addWildcardEvaluator(evaluator);
        }

        return ret;
    }

    private void insert(TrieNode<T> currentRoot, String resource, boolean isRecursive, T evaluator) {

        TrieNode<T>   curr       = currentRoot;
        final String  prefix     = getNonWildcardPrefix(resource);
        final boolean isWildcard = prefix.length() != resource.length();

        if (StringUtils.isNotEmpty(prefix)) {
            curr = curr.getOrCreateChild(prefix);
        }

        if(isWildcard || isRecursive) {
            curr.addWildcardEvaluator(evaluator);
        } else {
            curr.addEvaluator(evaluator);
        }

    }

    private String getNonWildcardPrefix(String str) {

        int minIndex = str.length();

        for (int i = 0; i < wildcardChars.length(); i++) {
            int index = str.indexOf(wildcardChars.charAt(i));

            if (index != -1 && index < minIndex) {
                minIndex = index;
            }
        }

        return str.substring(0, minIndex);
    }

    private Set<T> getEvaluatorsForResource(String resource) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("==> RangerResourceTrie.getEvaluatorsForResource(" + resource + ")");
        }

        RangerPerfTracer perf = null;

        if(RangerPerfTracer.isPerfTraceEnabled(PERF_TRIE_OP_LOG)) {
            perf = RangerPerfTracer.getPerfTracer(PERF_TRIE_OP_LOG, "RangerResourceTrie.getEvaluatorsForResource(resource=" + resource + ")");
        }

        TrieNode<T> curr   = root;
        TrieNode<T> parent = null;
        final int   len    = resource.length();
        int         i      = 0;

        while (i < len) {
            if (!isOptimizedForRetrieval) {
                curr.setupIfNeeded(parent);
            }

            final TrieNode<T> child = curr.getChild(getLookupChar(resource, i));

            if (child == null) {
                break;
            }

            final String childStr = child.getStr();

            if (!resource.regionMatches(optIgnoreCase, i, childStr, 0, childStr.length())) {
                break;
            }

            parent = curr;
            curr = child;
            i += childStr.length();
        }

        if (!isOptimizedForRetrieval) {
            curr.setupIfNeeded(parent);
        }

        Set<T> ret = i == len ? curr.getEvaluators() : curr.getWildcardEvaluators();

        RangerPerfTracer.logAlways(perf);

        if(LOG.isDebugEnabled()) {
            LOG.debug("<== RangerResourceTrie.getEvaluatorsForResource(" + resource + "): evaluatorCount=" + (ret == null ? 0 : ret.size()));
        }

        return ret;
    }

    private TrieNode<T> getNodeForResource(String resource) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("==> RangerResourceTrie.getNodeForResource(" + resource + ")");
        }

        RangerPerfTracer perf = null;

        if(RangerPerfTracer.isPerfTraceEnabled(PERF_TRIE_OP_LOG)) {
            perf = RangerPerfTracer.getPerfTracer(PERF_TRIE_OP_LOG, "RangerResourceTrie.getNodeForResource(resource=" + resource + ")");
        }

        TrieNode<T> curr   = root;
        final int   len    = resource.length();
        int         i      = 0;

        while (i < len) {

            final TrieNode<T> child = curr.getChild(getLookupChar(resource, i));

            if (child == null) {
                break;
            }

            final String childStr = child.getStr();

            if (!resource.regionMatches(optIgnoreCase, i, childStr, 0, childStr.length())) {
                break;
            }

            curr = child;
            i += childStr.length();
        }

        RangerPerfTracer.logAlways(perf);

        if(LOG.isDebugEnabled()) {
            LOG.debug("<== RangerResourceTrie.getNodeForResource(" + resource + ")");
        }

        return curr;
    }

    private Set<T> getEvaluatorsForResources(Collection<String> resources) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("==> RangerResourceTrie.getEvaluatorsForResources(" + resources + ")");
        }

        Set<T>      ret           = null;
        Map<Long, T> evaluatorsMap = null;

        for (String resource : resources) {
            Set<T> resourceEvaluators = getEvaluatorsForResource(resource);

            if (CollectionUtils.isEmpty(resourceEvaluators)) {
                continue;
            }

            if (evaluatorsMap == null) {
                if (ret == null) { // first resource: don't create map yet
                    ret = resourceEvaluators;
                } else if (ret != resourceEvaluators) { // if evaluator list is same as earlier resources, retain the list, else create a map
                    evaluatorsMap = new HashMap<>();

                    for (T evaluator : ret) {
                        evaluatorsMap.put(evaluator.getId(), evaluator);
                    }

                    ret = null;
                }
            }

            if (evaluatorsMap != null) {
                for (T evaluator : resourceEvaluators) {
                    evaluatorsMap.put(evaluator.getId(), evaluator);
                }
            }
        }

        if (ret == null && evaluatorsMap != null) {
            ret = new HashSet<>(evaluatorsMap.values());
        }

        if(LOG.isDebugEnabled()) {
            LOG.debug("<== RangerResourceTrie.getEvaluatorsForResources(" + resources + "): evaluatorCount=" + (ret == null ? 0 : ret.size()));
        }

        return ret;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        TrieData trieData = getTrieData();

        sb.append("resourceName=").append(resourceDef.getName());
        sb.append("; optIgnoreCase=").append(optIgnoreCase);
        sb.append("; optWildcard=").append(optWildcard);
        sb.append("; wildcardChars=").append(wildcardChars);
        sb.append("; nodeCount=").append(trieData.nodeCount);
        sb.append("; leafNodeCount=").append(trieData.leafNodeCount);
        sb.append("; singleChildNodeCount=").append(trieData.singleChildNodeCount);
        sb.append("; maxDepth=").append(trieData.maxDepth);
        sb.append("; evaluatorListCount=").append(trieData.evaluatorListCount);
        sb.append("; wildcardEvaluatorListCount=").append(trieData.wildcardEvaluatorListCount);
        sb.append("; evaluatorListRefCount=").append(trieData.evaluatorListRefCount);
        sb.append("; wildcardEvaluatorListRefCount=").append(trieData.wildcardEvaluatorListRefCount);

        return sb.toString();
    }

    class ResourceTrieBuilderThread extends Thread {

        class WorkItem {
            final String  resourceName;
            final boolean isRecursive;
            final T       evaluator;

            WorkItem(String resourceName, boolean isRecursive, T evaluator) {
                this.resourceName   = resourceName;
                this.isRecursive    = isRecursive;
                this.evaluator      = evaluator;
            }
            @Override
            public String toString() {
                return
                "resourceName=" + resourceName +
                "isRecursive=" + isRecursive +
                "evaluator=" + (evaluator != null? evaluator.getId() : null);
            }
        }

        private final   TrieNode<T>             thisRoot  = new TrieNode<>(null);
        private final   BlockingQueue<WorkItem> workQueue = new LinkedBlockingQueue<>();

        ResourceTrieBuilderThread() {
        }

        void add(String resourceName, boolean isRecursive, T evaluator) throws InterruptedException {
            workQueue.put(new WorkItem(resourceName, isRecursive, evaluator));
        }

        Map<Character, TrieNode<T>> getSubtrees() { return thisRoot.getChildren(); }

        @Override
        public void run() {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Running " + this);
            }

            while (true) {
                final WorkItem workItem;

                try {
                    workItem = workQueue.take();
                } catch (InterruptedException exception) {
                    LOG.error("Thread=" + this + " is interrupted", exception);

                    break;
                }

                if (workItem.evaluator != null) {
                    insert(thisRoot, workItem.resourceName, workItem.isRecursive, workItem.evaluator);
                } else {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Received termination signal. " + workItem);
                    }
                    break;
                }
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Exiting " + this);
            }
        }
    }

    static class TrieData {
        int nodeCount;
        int leafNodeCount;
        int singleChildNodeCount;
        int maxDepth;
        int evaluatorListCount;
        int wildcardEvaluatorListCount;
        int evaluatorListRefCount;
        int wildcardEvaluatorListRefCount;
    }

    private class TrieNode<U extends T> {
        private          String                      str;
        private          TrieNode<U>                 parent;
        private final    Map<Character, TrieNode<U>> children = new HashMap<>();
        private          Set<U>                      evaluators;
        private          Set<U>                      wildcardEvaluators;
        private          boolean                     isSharingParentWildcardEvaluators;
        private volatile boolean                     isSetup = false;

        TrieNode(String str) {
            this.str = str;
        }

        String getStr() {
            return str;
        }

        void setStr(String str) {
            this.str = str;
        }

        TrieNode<U> getParent() {
            return parent;
        }

        void setParent(TrieNode<U> parent) {
            this.parent = parent;
        }

        Map<Character, TrieNode<U>> getChildren() {
            return children;
        }

        Set<U> getEvaluators() {
            return evaluators;
        }

        Set<U> getWildcardEvaluators() {
            return wildcardEvaluators;
        }

        TrieNode<U> getChild(Character ch) {
            return children.get(ch);
        }

        void populateTrieData(RangerResourceTrie.TrieData trieData) {
            trieData.nodeCount++;

            if (wildcardEvaluators != null) {
                if (isSharingParentWildcardEvaluators) {
                    trieData.wildcardEvaluatorListRefCount++;
                } else {
                    trieData.wildcardEvaluatorListCount++;
                }
            }

            if (evaluators != null) {
                if (evaluators == wildcardEvaluators) {
                    trieData.evaluatorListRefCount++;
                } else {
                    trieData.evaluatorListCount++;
                }
            }

            if (!children.isEmpty()) {
                if (children.size() == 1) {
                    trieData.singleChildNodeCount++;
                }

                for (Map.Entry<Character, TrieNode<U>> entry : children.entrySet()) {
                    TrieNode child = entry.getValue();

                    child.populateTrieData(trieData);
                }
            } else {
                trieData.leafNodeCount++;
            }
        }

        int getMaxDepth() {
            int ret = 0;

            for (Map.Entry<Character, TrieNode<U>> entry : children.entrySet()) {
                TrieNode<U> child = entry.getValue();

                int maxChildDepth = child.getMaxDepth();

                if (maxChildDepth > ret) {
                    ret = maxChildDepth;
                }
            }

            return ret + 1;
        }

        TrieNode<U> getOrCreateChild(String str) {
            int len = str.length();

            TrieNode<U> child = children.get(getLookupChar(str, 0));

            if (child == null) {
                child = new TrieNode<>(str);
                addChild(child);
            } else {
                final String childStr = child.getStr();
                final int childStrLen = childStr.length();

                final boolean isExactMatch = optIgnoreCase ? StringUtils.equalsIgnoreCase(childStr, str) : StringUtils.equals(childStr, str);

                if (!isExactMatch) {
                    final int numOfCharactersToMatch = Math.min(childStrLen, len);
                    int index = 1;
                    for (; index < numOfCharactersToMatch; index++) {
                        if (getLookupChar(childStr, index) != getLookupChar(str, index)) {
                            break;
                        }
                    }
                    if (index == numOfCharactersToMatch) {
                        // Matched all
                        if (childStrLen > len) {
                            // Existing node has longer string, need to break up this node
                            TrieNode<U> newChild = new TrieNode<>(str);
                            this.addChild(newChild);
                            child.setStr(childStr.substring(index));
                            newChild.addChild(child);
                            child = newChild;
                        } else {
                            // This is a longer string, build a child with leftover string
                            child = child.getOrCreateChild(str.substring(index));
                        }
                    } else {
                        // Partial match for both; both have leftovers
                        String matchedPart = str.substring(0, index);
                        TrieNode<U> newChild = new TrieNode<>(matchedPart);
                        this.addChild(newChild);
                        child.setStr(childStr.substring(index));
                        newChild.addChild(child);
                        child = newChild.getOrCreateChild(str.substring(index));
                    }
                }
            }

            return child;
        }

        private void addChild(TrieNode<U> child) {
            children.put(getLookupChar(child.getStr(), 0), child);
            child.setParent(this);
        }

        void addEvaluator(U evaluator) {
            if (evaluators == null) {
                evaluators = new HashSet<>();
            }
            evaluators.add(evaluator);
        }

        void addWildcardEvaluator(U evaluator) {
            if (wildcardEvaluators == null) {
                wildcardEvaluators = new HashSet<>();
            }

            if (!wildcardEvaluators.contains(evaluator)) {
                wildcardEvaluators.add(evaluator);
                undoSetup();
            }
        }

        void removeEvaluator(U evaluator) {
            if (CollectionUtils.isNotEmpty(evaluators) && evaluators.contains(evaluator)) {
                evaluators.remove(evaluator);
                if (CollectionUtils.isEmpty(evaluators)) {
                    evaluators = null;
                }
            }
        }

        boolean removeWildcardEvaluator(U evaluator) {
            if (CollectionUtils.isNotEmpty(wildcardEvaluators) && wildcardEvaluators.contains(evaluator)) {
                wildcardEvaluators.remove(evaluator);
                if (CollectionUtils.isEmpty(wildcardEvaluators)) {
                    wildcardEvaluators = null;
                }
                undoSetup();
                return true;
            } else {
                return false;
            }
        }

        void undoSetup() {
            if (isSetup) {
                if (evaluators != null) {

                    for (TrieNode<U> child : children.values()) {
                        child.undoSetup();
                    }

                    if (evaluators == wildcardEvaluators) {
                        evaluators = null;
                    } else {
                        if (wildcardEvaluators != null) {
                            evaluators.removeAll(wildcardEvaluators);

                            if (CollectionUtils.isEmpty(evaluators)) {
                                evaluators = null;
                            }

                            if (isSharingParentWildcardEvaluators) {
                                wildcardEvaluators = null;
                            } else {
                                Set<U> parentWildcardEvaluators = getParent() == null ? null : getParent().getWildcardEvaluators();

                                if (parentWildcardEvaluators != null) {
                                    wildcardEvaluators.removeAll(parentWildcardEvaluators);

                                    if (CollectionUtils.isEmpty(wildcardEvaluators)) {
                                        wildcardEvaluators = null;
                                    }
                                }
                            }
                        }
                    }
                }
                isSharingParentWildcardEvaluators = false;
                isSetup = false;
            }
        }

        void wrapUpUpdate() {
            if (isOptimizedForRetrieval) {
                RangerPerfTracer postSetupPerf = null;

                if (RangerPerfTracer.isPerfTraceEnabled(PERF_TRIE_INIT_LOG)) {
                    postSetupPerf = RangerPerfTracer.getPerfTracer(PERF_TRIE_INIT_LOG, "RangerResourceTrie.init(name=" + resourceDef.getName() + "-postSetup)");
                }

                postSetup(null);

                RangerPerfTracer.logAlways(postSetupPerf);
            }
        }

        void postSetup(Set<U> parentWildcardEvaluators) {

            setup(parentWildcardEvaluators);

            for (Map.Entry<Character, TrieNode<U>> entry : children.entrySet()) {
                TrieNode<U> child = entry.getValue();

                child.postSetup(wildcardEvaluators);
            }

        }

        void setupIfNeeded(TrieNode<U> parent) {

            boolean setupNeeded = !isSetup;

            if (setupNeeded) {
                synchronized (this.children) {
                    setupNeeded = !isSetup;

                    if (setupNeeded) {
                        setup(parent == null ? null : parent.getWildcardEvaluators());
                        if (TRACE_LOG.isTraceEnabled()) {
                            StringBuilder sb = new StringBuilder();
                            this.toString(sb);
                            TRACE_LOG.trace("Set up is completed for this TriNode as a part of access evaluation : [" + sb + "]");
                        }
                    }
                }
            }
        }

        void setup(Set<U> parentWildcardEvaluators) {
            if (!isSetup) {
                // finalize wildcard-evaluators list by including parent's wildcard evaluators
                if (parentWildcardEvaluators != null) {
                    if (CollectionUtils.isEmpty(this.wildcardEvaluators)) {
                        this.wildcardEvaluators = parentWildcardEvaluators;
                    } else {
                        for (U evaluator : parentWildcardEvaluators) {
                            addWildcardEvaluator(evaluator);
                        }
                    }
                }
                this.isSharingParentWildcardEvaluators = wildcardEvaluators == parentWildcardEvaluators;

                // finalize evaluators list by including wildcard evaluators
                if (wildcardEvaluators != null) {
                    if (CollectionUtils.isEmpty(this.evaluators)) {
                        this.evaluators = wildcardEvaluators;
                    } else {
                        for (U evaluator : wildcardEvaluators) {
                            addEvaluator(evaluator);
                        }
                    }
                }

                isSetup = true;
            }
        }

        private void removeEvaluatorFromSubtree(U evaluator) {
            if (removeWildcardEvaluator(evaluator)) {
                for (Map.Entry<Character, TrieNode<U>> entry : children.entrySet()) {
                    entry.getValue().removeEvaluatorFromSubtree(evaluator);
                }
            }

            removeEvaluator(evaluator);

        }

        public void toString(StringBuilder sb) {
            String nodeValue = this.str;

            sb.append("nodeValue=").append(nodeValue);
            sb.append("; isSetup=").append(isSetup);
            sb.append("; isSharingParentWildcardEvaluators=").append(isSharingParentWildcardEvaluators);
            sb.append("; childCount=").append(children.size());
            sb.append("; evaluators=[ ");
            if (evaluators != null) {
                for (U evaluator : evaluators) {
                    sb.append(evaluator.getId()).append(" ");
                }
            }
            sb.append("]");

            sb.append("; wildcardEvaluators=[ ");
            if (wildcardEvaluators != null) {
                for (U evaluator : wildcardEvaluators) {
                    sb.append(evaluator.getId()).append(" ");
                }
            }
        }

        public void toString(String prefix, StringBuilder sb) {
            String nodeValue = prefix + (str != null ? str : "");

            sb.append(prefix);
            toString(sb);
            sb.append("]\n");

            for (Map.Entry<Character, TrieNode<U>> entry : children.entrySet()) {
                TrieNode<U> child = entry.getValue();

                child.toString(nodeValue, sb);
            }

        }

        public void clear() {
            children.clear();

            evaluators         = null;
            wildcardEvaluators = null;
        }
    }
}
