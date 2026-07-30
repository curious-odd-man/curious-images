package com.github.curiousoddman.curious_images.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiModelLink {
    private String filename;
    private String url;
}
