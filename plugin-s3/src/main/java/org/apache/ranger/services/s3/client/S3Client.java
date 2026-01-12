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

package org.apache.ranger.services.s3.client;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.ObjectListing;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ranger.plugin.util.PasswordUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * S3Client provides connectivity to Amazon S3 compatible storage services
 * and implements resource discovery operations for Apache Ranger integration.
 * 
 * <p>This client supports:
 * <ul>
 *   <li>Connection testing and validation</li>
 *   <li>Bucket listing and filtering</li>
 *   <li>Path-based resource lookup with auto-complete</li>
 *   <li>Custom S3-compatible endpoints (e.g., MinIO, Ceph)</li>
 * </ul>
 * 
 * <p>The client uses path-style access for compatibility with S3-compatible services.
 */
public class S3Client {
    private static final Log LOG = LogFactory.getLog(S3Client.class);
    private static final String DEFAULT_AWS_REGION = "us-east-1";
    private static final int MAX_RESOURCE_RESULTS = 50;

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String awsRegion;

    /**
     * Constructs an S3Client with the provided configuration.
     * 
     * @param configs Map containing connection parameters:
     *                - endpoint: S3 service URL (e.g., s3://localhost:9000)
     *                - accesskey: AWS access key or compatible credential
     *                - password: Encrypted secret key (will be decrypted)
     *                - region: AWS region (optional, defaults to us-east-1)
     * @throws Exception if required configuration is missing or invalid
     */
    public S3Client(Map<String, String> configs) throws Exception {
        this.endpoint = configs.get("endpoint");
        this.accessKey = configs.get("accesskey");
        this.secretKey = PasswordUtils.decryptPassword(configs.get("password"));
        this.awsRegion = configs.getOrDefault("region", DEFAULT_AWS_REGION);

        validateConfiguration();
    }

    /**
     * Validates the S3 client configuration.
     * 
     * @throws Exception if configuration is invalid
     */
    private void validateConfiguration() throws Exception {
        if (this.endpoint == null || this.endpoint.isEmpty()) {
            logError("Configuration 'endpoint' is required. Please provide URL in format s3://host:port");
        }
        if (this.accessKey == null || this.accessKey.isEmpty()) {
            logError("Configuration 'accesskey' is required");
        }
        if (this.secretKey == null || this.secretKey.isEmpty()) {
            logError("Configuration 'password' (secret key) is required");
        }
    }

    /**
     * Logs an error message and throws an exception.
     * 
     * @param errorMessage Error message to log and throw
     * @throws Exception always throws with the provided message
     */
    private static void logError(String errorMessage) throws Exception {
        LOG.error(errorMessage);
        throw new Exception(errorMessage);
    }

    /**
     * Creates and configures an Amazon S3 client instance.
     * 
     * <p>The client is configured with:
     * <ul>
     *   <li>Static AWS credentials</li>
     *   <li>S3SignerType for compatibility with older S3 implementations</li>
     *   <li>Path-style access for custom endpoints</li>
     * </ul>
     * 
     * @return Configured AmazonS3 client instance
     */
    private AmazonS3 getAWSClient() {
        AWSCredentials credentials = new BasicAWSCredentials(this.accessKey, this.secretKey);
        
        // S3SignerType is required until HTTP client libraries allow raw User-Agent headers.
        // Some proxies (e.g., Airlock) modify User-Agent causing signature mismatch.
        ClientConfiguration conf = new ClientConfiguration();
        conf.setSignerOverride("S3SignerType");

        AmazonS3ClientBuilder client = AmazonS3ClientBuilder
                .standard()
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .withClientConfiguration(conf)
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, awsRegion));

        client.setPathStyleAccessEnabled(true);
        return client.build();
    }

    /**
     * Tests connectivity to the S3 service by attempting to list buckets.
     * 
     * @return Map containing connection test results with fields:
     *         - connectivityStatus: true/false
     *         - message: Success or error message
     */
    public Map<String, Object> connectionTest() {
        Map<String, Object> responseData = new HashMap<String, Object>();

        try {
            List<Bucket> buckets = getAWSClient().listBuckets();

            if (buckets == null || buckets.isEmpty()) {
                responseData.put("connectivityStatus", false);
                responseData.put("message", "Connection successful but no buckets found. Verify permissions.");
                LOG.warn("S3 connection test: no buckets returned");
            } else {
                responseData.put("connectivityStatus", true);
                responseData.put("message", "Connection test successful. Found " + buckets.size() + " bucket(s).");
                if (LOG.isDebugEnabled()) {
                    LOG.debug("S3 connection test successful. Buckets: " + buckets.size());
                }
            }
        } catch (Exception e) {
            responseData.put("connectivityStatus", false);
            responseData.put("message", "Connection failed: " + e.getMessage());
            LOG.error("S3 connection test failed", e);
        }
        return responseData;
    }

    /**
     * Removes leading slash from user input if present.
     * 
     * @param userInput Input string that may start with '/'
     * @return String without leading slash
     */
    private String removeLeadingSlash(final String userInput) {
        if (userInput != null && userInput.startsWith("/")) {
            return userInput.substring(1);
        }
        return userInput;
    }

    /**
     * Retrieves S3 resource paths (buckets and pseudo-directories) matching the user input.
     * 
     * <p>This method implements intelligent auto-complete functionality:
     * <ul>
     *   <li>Returns matching bucket names if input is a bucket prefix</li>
     *   <li>Returns pseudo-directories within a bucket if input includes path</li>
     *   <li>Results are sorted and limited to {@link #MAX_RESOURCE_RESULTS}</li>
     * </ul>
     * 
     * @param userInput User-provided search string (e.g., "/my-bucket" or "/my-bucket/folder/")
     * @return List of matching resource paths, each starting with '/'
     */
    public List<String> getResourcePaths(final String userInput) {
        Supplier<Stream<Bucket>> buckets = () -> getAWSClient().listBuckets().stream();
        String[] userInputSplit = removeLeadingSlash(userInput).split("/");
        String bucketFilter = userInputSplit[0];
        String subdirFilter;

        if (userInputSplit.length >= 2) {
            subdirFilter = userInput.substring(removeLeadingSlash(userInput).indexOf("/") + 2);
        } else {
            subdirFilter = "";
        }

        List<String> bucketsPaths = buckets
                .get()
                .filter(b -> b.getName().startsWith(bucketFilter))
                .flatMap(b -> {
                    if (subdirFilter.length() > 0 || userInput.endsWith("/")) {
                        return getBucketPseudoDirectories(b.getName(), subdirFilter).stream();
                    } else {
                        return buckets.get()
                                .filter(sb -> sb.getName().startsWith(bucketFilter))
                                .map(sb -> String.format("/%s", sb.getName()));
                    }
                })
                .distinct()
                .sorted()
                .limit(MAX_RESOURCE_RESULTS)
                .collect(Collectors.toList());

        return bucketsPaths;
    }

    /**
     * Retrieves pseudo-directories within an S3 bucket.
     * 
     * <p>S3 does not have true directories, but this method infers directory structure
     * from object keys containing '/' characters. Objects with size 0 are treated as
     * directory markers.
     * 
     * @param bucket Bucket name to search within
     * @param subdirFilter Optional prefix filter for subdirectories
     * @return List of pseudo-directory paths in format "/bucket/path/"
     */
    public List<String> getBucketPseudoDirectories(final String bucket, final String subdirFilter) {
        ObjectListing bucketObjects = getAWSClient().listObjects(bucket);

        List<String> pseudoDirsFiltered = bucketObjects
                .getObjectSummaries()
                .stream()
                .filter(p -> {
                    if (subdirFilter != null && subdirFilter.length() > 0) {
                        return p.getKey().startsWith(subdirFilter);
                    } else {
                        return true;
                    }
                })
                .map(p -> {
                    // Objects with size 0 are typically directory markers
                    if (p.getSize() == 0) {
                        return String.format("/%s/%s", bucket, p.getKey());
                    } else {
                        // Extract directory path from object key
                        int endIndex = p.getKey().contains("/") ? p.getKey().lastIndexOf("/") : 0;
                        if (endIndex > 0) {
                            return String.format("/%s/%s/", bucket, p.getKey().substring(0, endIndex));
                        } else {
                            return String.format("/%s/", bucket);
                        }
                    }
                })
                .collect(Collectors.toList());

        return pseudoDirsFiltered;
    }
}
