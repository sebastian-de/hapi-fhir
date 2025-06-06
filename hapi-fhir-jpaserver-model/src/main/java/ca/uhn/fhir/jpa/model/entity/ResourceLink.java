/*
 * #%L
 * HAPI FHIR JPA Model
 * %%
 * Copyright (C) 2014 - 2025 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ca.uhn.fhir.jpa.model.entity;

import ca.uhn.fhir.jpa.model.dao.JpaPid;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.Transient;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hl7.fhir.instance.model.api.IIdType;

import java.time.LocalDate;
import java.util.Date;

@Entity
@Table(
		name = "HFJ_RES_LINK",
		indexes = {
			// We need to join both ways, so index from src->tgt and from tgt->src.
			// From src->tgt, rows are usually written all together as part of ingestion - keep the index small, and
			// read blocks as needed.
			@Index(name = "IDX_RL_SRC", columnList = "SRC_RESOURCE_ID"),
			// But from tgt->src, include all the match columns. Targets will usually be randomly distributed - each row
			// in separate block.
			@Index(
					name = "IDX_RL_TGT_v2",
					columnList = "TARGET_RESOURCE_ID, SRC_PATH, SRC_RESOURCE_ID, TARGET_RESOURCE_TYPE,PARTITION_ID")
		})
@IdClass(IdAndPartitionId.class)
public class ResourceLink extends BaseResourceIndex {

	public static final int SRC_PATH_LENGTH = 500;
	private static final long serialVersionUID = 1L;
	public static final String TARGET_RES_PARTITION_ID = "TARGET_RES_PARTITION_ID";
	public static final String TARGET_RESOURCE_ID = "TARGET_RESOURCE_ID";
	public static final String FK_RESLINK_TARGET = "FK_RESLINK_TARGET";

	@GenericGenerator(name = "SEQ_RESLINK_ID", type = ca.uhn.fhir.jpa.model.dialect.HapiSequenceStyleGenerator.class)
	@GeneratedValue(strategy = GenerationType.AUTO, generator = "SEQ_RESLINK_ID")
	@Id
	@Column(name = "PID")
	private Long myId;

	@Column(name = "SRC_PATH", length = SRC_PATH_LENGTH, nullable = false)
	private String mySourcePath;

	@ManyToOne(
			optional = false,
			fetch = FetchType.LAZY,
			cascade = {})
	@JoinColumns(
			value = {
				@JoinColumn(
						name = "SRC_RESOURCE_ID",
						referencedColumnName = "RES_ID",
						insertable = false,
						updatable = false,
						nullable = false),
				@JoinColumn(
						name = "PARTITION_ID",
						referencedColumnName = "PARTITION_ID",
						insertable = false,
						updatable = false,
						nullable = false)
			},
			foreignKey = @ForeignKey(name = "FK_RESLINK_SOURCE"))
	private ResourceTable mySourceResource;

	@Column(name = "SRC_RESOURCE_ID", nullable = false)
	private Long mySourceResourcePid;

	@Column(name = "SOURCE_RESOURCE_TYPE", updatable = false, nullable = false, length = ResourceTable.RESTYPE_LEN)
	@FullTextField
	private String mySourceResourceType;

	@ManyToOne(optional = true, fetch = FetchType.EAGER)
	@JoinColumns(
			value = {
				@JoinColumn(
						name = TARGET_RESOURCE_ID,
						referencedColumnName = "RES_ID",
						nullable = true,
						insertable = false,
						updatable = false),
				@JoinColumn(
						name = TARGET_RES_PARTITION_ID,
						referencedColumnName = "PARTITION_ID",
						nullable = true,
						insertable = false,
						updatable = false),
			},
			/*
			 * TODO: We need to drop this constraint because it affects performance in pretty
			 *  terrible ways on a lot of platforms. But a Hibernate bug present in Hibernate 6.6.4
			 *  makes it impossible.
			 *  See: https://hibernate.atlassian.net/browse/HHH-19046
			 */
			foreignKey = @ForeignKey(name = FK_RESLINK_TARGET))
	private ResourceTable myTargetResource;

	@Transient
	private ResourceTable myTransientTargetResource;

	@Column(name = TARGET_RESOURCE_ID, insertable = true, updatable = true, nullable = true)
	@FullTextField
	private Long myTargetResourcePid;

	@Column(name = "TARGET_RESOURCE_TYPE", nullable = false, length = ResourceTable.RESTYPE_LEN)
	@FullTextField
	private String myTargetResourceType;

	@Column(name = "TARGET_RESOURCE_URL", length = 200, nullable = true)
	@FullTextField
	private String myTargetResourceUrl;

	@Column(name = "TARGET_RESOURCE_VERSION", nullable = true)
	private Long myTargetResourceVersion;

	@FullTextField
	@Column(name = "SP_UPDATED", nullable = true) // TODO: make this false after HAPI 2.3
	@Temporal(TemporalType.TIMESTAMP)
	private Date myUpdated;

	@Transient
	private transient String myTargetResourceId;

	@Column(name = TARGET_RES_PARTITION_ID, nullable = true)
	private Integer myTargetResourcePartitionId;

	@Column(name = "TARGET_RES_PARTITION_DATE", nullable = true)
	private LocalDate myTargetResourcePartitionDate;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(
			name = "SRC_RES_TYPE_ID",
			referencedColumnName = "RES_TYPE_ID",
			foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT),
			insertable = false,
			updatable = false,
			nullable = true)
	private ResourceTypeEntity mySourceResTypeEntity;

	@Column(name = "SRC_RES_TYPE_ID", nullable = true)
	private Short mySourceResourceTypeId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(
			name = "TARGET_RES_TYPE_ID",
			referencedColumnName = "RES_TYPE_ID",
			foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT),
			insertable = false,
			updatable = false,
			nullable = true)
	private ResourceTypeEntity myTargetResTypeEntity;

	@Column(name = "TARGET_RES_TYPE_ID", nullable = true)
	private Short myTargetResourceTypeId;
	/**
	 * Constructor
	 */
	public ResourceLink() {
		super();
	}

	public Long getTargetResourceVersion() {
		return myTargetResourceVersion;
	}

	public void setTargetResourceVersion(Long theTargetResourceVersion) {
		myTargetResourceVersion = theTargetResourceVersion;
	}

	public String getTargetResourceId() {
		if (myTargetResourceId == null && getTargetResource() != null) {
			myTargetResourceId = getTargetResource().getIdDt().getIdPart();
		}
		return myTargetResourceId;
	}

	public String getSourceResourceType() {
		return mySourceResourceType;
	}

	public String getTargetResourceType() {
		return myTargetResourceType;
	}

	@Override
	public boolean equals(Object theObj) {
		if (this == theObj) {
			return true;
		}
		if (theObj == null) {
			return false;
		}
		if (!(theObj instanceof ResourceLink)) {
			return false;
		}
		ResourceLink obj = (ResourceLink) theObj;
		EqualsBuilder b = new EqualsBuilder();
		b.append(mySourcePath, obj.mySourcePath);
		b.append(mySourceResource, obj.mySourceResource);
		b.append(myTargetResourceUrl, obj.myTargetResourceUrl);
		b.append(myTargetResourceType, obj.myTargetResourceType);
		b.append(myTargetResourceVersion, obj.myTargetResourceVersion);
		// In cases where we are extracting links from a resource that has not yet been persisted, the target resource
		// pid
		// will be null so we use the target resource id to differentiate instead
		if (getTargetResourcePid() == null) {
			b.append(getTargetResourceId(), obj.getTargetResourceId());
		} else {
			b.append(getTargetResourcePid(), obj.getTargetResourcePid());
		}
		return b.isEquals();
	}

	/**
	 * ResourceLink.myTargetResource field is immutable.Transient ResourceLink.myTransientTargetResource property
	 * is used instead, allowing it to be updated via the ResourceLink#copyMutableValuesFrom method
	 * when ResourceLink table row is reused.
	 */
	@PostLoad
	public void postLoad() {
		myTransientTargetResource = myTargetResource;
	}

	@Override
	public <T extends BaseResourceIndex> void copyMutableValuesFrom(T theSource) {
		ResourceLink source = (ResourceLink) theSource;
		mySourcePath = source.getSourcePath();
		myTransientTargetResource = source.getTargetResource();
		myTargetResourceId = source.getTargetResourceId();
		myTargetResourcePid = source.getTargetResourcePid();
		myTargetResourceType = source.getTargetResourceType();
		myTargetResourceTypeId = source.getTargetResourceTypeId();
		myTargetResourceVersion = source.getTargetResourceVersion();
		myTargetResourceUrl = source.getTargetResourceUrl();
		myTargetResourcePartitionId = source.getTargetResourcePartitionId();
		myTargetResourcePartitionDate = source.getTargetResourcePartitionDate();
	}

	@Override
	public void setResourceId(Long theResourceId) {
		mySourceResourcePid = theResourceId;
	}

	public String getSourcePath() {
		return mySourcePath;
	}

	public void setSourcePath(String theSourcePath) {
		mySourcePath = theSourcePath;
	}

	public JpaPid getSourceResourcePk() {
		return JpaPid.fromId(mySourceResourcePid, myPartitionIdValue);
	}

	public ResourceTable getSourceResource() {
		return mySourceResource;
	}

	public void setSourceResource(ResourceTable theSourceResource) {
		mySourceResource = theSourceResource;
		mySourceResourcePid = theSourceResource.getId().getId();
		mySourceResourceType = theSourceResource.getResourceType();
		mySourceResourceTypeId = theSourceResource.getResourceTypeId();
		setPartitionId(theSourceResource.getPartitionId());
	}

	public void setTargetResource(String theResourceType, Long theResourcePid, String theTargetResourceId) {
		Validate.notBlank(theResourceType);

		myTargetResourceType = theResourceType;
		myTargetResourcePid = theResourcePid;
		myTargetResourceId = theTargetResourceId;
	}

	public String getTargetResourceUrl() {
		return myTargetResourceUrl;
	}

	public void setTargetResourceUrl(IIdType theTargetResourceUrl) {
		Validate.isTrue(theTargetResourceUrl.hasBaseUrl());
		Validate.isTrue(theTargetResourceUrl.hasResourceType());

		//		if (theTargetResourceUrl.hasIdPart()) {
		// do nothing
		//		} else {
		// Must have set an url like http://example.org/something
		// We treat 'something' as the resource type because of fix for #659. Prior to #659 fix, 'something' was
		// treated as the id and 'example.org' was treated as the resource type
		// Maybe log a warning?
		//		}

		myTargetResourceType = theTargetResourceUrl.getResourceType();
		myTargetResourceUrl = theTargetResourceUrl.getValue();
	}

	public Long getTargetResourcePid() {
		return myTargetResourcePid;
	}

	public void setTargetResourceUrlCanonical(String theTargetResourceUrl) {
		Validate.notBlank(theTargetResourceUrl);

		myTargetResourceType = "(unknown)";
		myTargetResourceUrl = theTargetResourceUrl;
	}

	public Date getUpdated() {
		return myUpdated;
	}

	public void setUpdated(Date theUpdated) {
		myUpdated = theUpdated;
	}

	@Override
	public Long getId() {
		return myId;
	}

	@Override
	public void setId(Long theId) {
		myId = theId;
	}

	public LocalDate getTargetResourcePartitionDate() {
		return myTargetResourcePartitionDate;
	}

	public Integer getTargetResourcePartitionId() {
		return myTargetResourcePartitionId;
	}

	public ResourceLink setTargetResourcePartitionId(PartitionablePartitionId theTargetResourcePartitionId) {
		myTargetResourcePartitionId = null;
		myTargetResourcePartitionDate = null;
		if (theTargetResourcePartitionId != null) {
			myTargetResourcePartitionId = theTargetResourcePartitionId.getPartitionId();
			myTargetResourcePartitionDate = theTargetResourcePartitionId.getPartitionDate();
		}
		return this;
	}

	public Short getSourceResourceTypeId() {
		return mySourceResourceTypeId;
	}

	public ResourceTypeEntity getSourceResTypeEntity() {
		return mySourceResTypeEntity;
	}

	public Short getTargetResourceTypeId() {
		return myTargetResourceTypeId;
	}

	public void setTargetResourceTypeId(Short theTargetResourceTypeId) {
		myTargetResourceTypeId = theTargetResourceTypeId;
	}

	public ResourceTypeEntity getTargetResTypeEntity() {
		return myTargetResTypeEntity;
	}

	@Override
	public void clearHashes() {
		// nothing right now
	}

	@Override
	public void calculateHashes() {
		// nothing right now
	}

	@Override
	public int hashCode() {
		HashCodeBuilder b = new HashCodeBuilder();
		b.append(mySourcePath);
		b.append(mySourceResource);
		b.append(myTargetResourceUrl);
		b.append(myTargetResourceVersion);

		// In cases where we are extracting links from a resource that has not yet been persisted, the target resource
		// pid
		// will be null so we use the target resource id to differentiate instead
		if (getTargetResourcePid() == null) {
			b.append(getTargetResourceId());
		} else {
			b.append(getTargetResourcePid());
		}
		return b.toHashCode();
	}

	@Override
	public String toString() {
		StringBuilder b = new StringBuilder();
		b.append("ResourceLink[");
		b.append("path=").append(mySourcePath);
		b.append(", srcResId=").append(mySourceResourcePid);
		b.append(", srcPartition=").append(myPartitionIdValue);
		b.append(", srcResTypeId=").append(getSourceResourceTypeId());
		b.append(", targetResId=").append(myTargetResourcePid);
		b.append(", targetPartition=").append(myTargetResourcePartitionId);
		b.append(", targetResType=").append(myTargetResourceType);
		b.append(", targetResTypeId=").append(getTargetResourceTypeId());
		b.append(", targetResVersion=").append(myTargetResourceVersion);
		b.append(", targetResUrl=").append(myTargetResourceUrl);

		b.append("]");
		return b.toString();
	}

	public ResourceTable getTargetResource() {
		return myTransientTargetResource;
	}

	/**
	 * Creates a clone of this resourcelink which doesn't contain the internal PID
	 * of the target resource.
	 */
	public ResourceLink cloneWithoutTargetPid() {
		ResourceLink retVal = new ResourceLink();
		retVal.mySourceResource = mySourceResource;
		retVal.mySourceResourcePid = mySourceResource.getId().getId();
		retVal.mySourceResourceType = mySourceResource.getResourceType();
		retVal.mySourceResourceTypeId = mySourceResource.getResourceTypeId();
		retVal.myPartitionIdValue = mySourceResource.getPartitionId().getPartitionId();
		retVal.mySourcePath = mySourcePath;
		retVal.myUpdated = myUpdated;
		retVal.myTargetResourceType = myTargetResourceType;
		retVal.myTargetResourceTypeId = myTargetResourceTypeId;
		if (myTargetResourceId != null) {
			retVal.myTargetResourceId = myTargetResourceId;
		} else if (getTargetResource() != null) {
			retVal.myTargetResourceId = getTargetResource().getIdDt().getIdPart();
			retVal.myTargetResourceTypeId = getTargetResource().getResourceTypeId();
		}
		retVal.myTargetResourceUrl = myTargetResourceUrl;
		retVal.myTargetResourceVersion = myTargetResourceVersion;
		return retVal;
	}

	public static ResourceLink forAbsoluteReference(
			String theSourcePath, ResourceTable theSourceResource, IIdType theTargetResourceUrl, Date theUpdated) {
		ResourceLink retVal = new ResourceLink();
		retVal.setSourcePath(theSourcePath);
		retVal.setSourceResource(theSourceResource);
		retVal.setTargetResourceUrl(theTargetResourceUrl);
		retVal.setUpdated(theUpdated);
		return retVal;
	}

	/**
	 * Factory for canonical URL
	 */
	public static ResourceLink forLogicalReference(
			String theSourcePath, ResourceTable theSourceResource, String theTargetResourceUrl, Date theUpdated) {
		ResourceLink retVal = new ResourceLink();
		retVal.setSourcePath(theSourcePath);
		retVal.setSourceResource(theSourceResource);
		retVal.setTargetResourceUrlCanonical(theTargetResourceUrl);
		retVal.setUpdated(theUpdated);
		return retVal;
	}

	public static ResourceLink forLocalReference(
			ResourceLinkForLocalReferenceParams theResourceLinkForLocalReferenceParams) {

		ResourceLink retVal = new ResourceLink();
		retVal.setSourcePath(theResourceLinkForLocalReferenceParams.getSourcePath());
		retVal.setSourceResource(theResourceLinkForLocalReferenceParams.getSourceResource());
		retVal.setTargetResource(
				theResourceLinkForLocalReferenceParams.getTargetResourceType(),
				theResourceLinkForLocalReferenceParams.getTargetResourcePid(),
				theResourceLinkForLocalReferenceParams.getTargetResourceId());

		retVal.setTargetResourcePartitionId(
				theResourceLinkForLocalReferenceParams.getTargetResourcePartitionablePartitionId());
		retVal.setTargetResourceVersion(theResourceLinkForLocalReferenceParams.getTargetResourceVersion());
		retVal.setUpdated(theResourceLinkForLocalReferenceParams.getUpdated());

		return retVal;
	}

	public static class ResourceLinkForLocalReferenceParams {
		private String mySourcePath;
		private ResourceTable mySourceResource;
		private String myTargetResourceType;
		private Long myTargetResourcePid;
		private String myTargetResourceId;
		private Date myUpdated;
		private Long myTargetResourceVersion;
		private PartitionablePartitionId myTargetResourcePartitionablePartitionId;

		public static ResourceLinkForLocalReferenceParams instance() {
			return new ResourceLinkForLocalReferenceParams();
		}

		public String getSourcePath() {
			return mySourcePath;
		}

		public ResourceLinkForLocalReferenceParams setSourcePath(String theSourcePath) {
			mySourcePath = theSourcePath;
			return this;
		}

		public ResourceTable getSourceResource() {
			return mySourceResource;
		}

		public ResourceLinkForLocalReferenceParams setSourceResource(ResourceTable theSourceResource) {
			mySourceResource = theSourceResource;
			return this;
		}

		public String getTargetResourceType() {
			return myTargetResourceType;
		}

		public ResourceLinkForLocalReferenceParams setTargetResourceType(String theTargetResourceType) {
			myTargetResourceType = theTargetResourceType;
			return this;
		}

		public Long getTargetResourcePid() {
			return myTargetResourcePid;
		}

		public ResourceLinkForLocalReferenceParams setTargetResourcePid(Long theTargetResourcePid) {
			myTargetResourcePid = theTargetResourcePid;
			return this;
		}

		public String getTargetResourceId() {
			return myTargetResourceId;
		}

		public ResourceLinkForLocalReferenceParams setTargetResourceId(String theTargetResourceId) {
			myTargetResourceId = theTargetResourceId;
			return this;
		}

		public Date getUpdated() {
			return myUpdated;
		}

		public ResourceLinkForLocalReferenceParams setUpdated(Date theUpdated) {
			myUpdated = theUpdated;
			return this;
		}

		public Long getTargetResourceVersion() {
			return myTargetResourceVersion;
		}

		/**
		 * @param theTargetResourceVersion This should only be populated if the reference actually had a version
		 */
		public ResourceLinkForLocalReferenceParams setTargetResourceVersion(Long theTargetResourceVersion) {
			myTargetResourceVersion = theTargetResourceVersion;
			return this;
		}

		public PartitionablePartitionId getTargetResourcePartitionablePartitionId() {
			return myTargetResourcePartitionablePartitionId;
		}

		public ResourceLinkForLocalReferenceParams setTargetResourcePartitionablePartitionId(
				PartitionablePartitionId theTargetResourcePartitionablePartitionId) {
			myTargetResourcePartitionablePartitionId = theTargetResourcePartitionablePartitionId;
			return this;
		}
	}
}
