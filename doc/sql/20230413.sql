CREATE TABLE `website_info`
(
    id             bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    name           varchar(50)  NOT NULL COMMENT '网站名',
    domain         varchar(50)  NOT NULL COMMENT '网站域名',
    keyword        varchar(50)  NOT NULL COMMENT 'SEO关键词',
    description    varchar(512) NOT NULL COMMENT '网站描述',
    qq             varchar(20)  NOT NULL COMMENT '站长QQ',
    logo           varchar(200) NOT NULL COMMENT '网站logo图片（默认）',
    logo_dark      varchar(200) NOT NULL COMMENT '网站logo图片（深色）',
    create_time    datetime     null comment '创建时间',
    create_user_id bigint       null comment '创建人ID',
    update_time    datetime     null comment '更新时间',
    update_user_id bigint       null comment '更新人ID',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='网站信息表';

            
                           
    
        
         
            

            
          


        
        


    
  
    
  


          
        

          
        
          
        
          
        
          
        
          
        


    
  
    
  
    
  
    
  
    
  
    
  


            
          


        
        

    
  
    
  


            
          


        
        

    
  
    
  


            
          


        
        

    
  
    
  

          
        
    
  


        
        
    
  

          
        
    
  