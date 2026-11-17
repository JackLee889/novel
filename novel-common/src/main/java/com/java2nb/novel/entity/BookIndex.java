package com.java2nb.novel.entity;

import javax.annotation.processing.Generated;
import java.util.Date;

public class BookIndex {
    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private Long id;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private Long bookId;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private Integer indexNum;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private String indexName;

    /**
     * [审查01] 来源站点的章节 ID。章节身份以它为准，而不是目录位置(index_num)：
     * 源站插入章节或目录重排时，按位置匹配会把已有章节的正文改写到错误的行上。
     */
    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private String sourceIndexId;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private Integer wordCount;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private Byte isVip;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private Integer bookPrice;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private String storageType;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private Date createTime;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    private Date updateTime;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public Long getId() {
        return id;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setId(Long id) {
        this.id = id;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public Long getBookId() {
        return bookId;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public Integer getIndexNum() {
        return indexNum;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setIndexNum(Integer indexNum) {
        this.indexNum = indexNum;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public String getIndexName() {
        return indexName;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setIndexName(String indexName) {
        this.indexName = indexName == null ? null : indexName.trim();
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public String getSourceIndexId() {
        return sourceIndexId;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setSourceIndexId(String sourceIndexId) {
        this.sourceIndexId = sourceIndexId == null ? null : sourceIndexId.trim();
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public Integer getWordCount() {
        return wordCount;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setWordCount(Integer wordCount) {
        this.wordCount = wordCount;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public Byte getIsVip() {
        return isVip;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setIsVip(Byte isVip) {
        this.isVip = isVip;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public Integer getBookPrice() {
        return bookPrice;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setBookPrice(Integer bookPrice) {
        this.bookPrice = bookPrice;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public String getStorageType() {
        return storageType;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setStorageType(String storageType) {
        this.storageType = storageType == null ? null : storageType.trim();
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public Date getCreateTime() {
        return createTime;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public Date getUpdateTime() {
        return updateTime;
    }

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
}