package com.weathercli.command;

import com.weathercli.exception.CLIException;
import com.weathercli.service.ModelService;

/**
 * models 命令 — 显示可用的 AI 大模型及 API Key 申请指引。
 */
public class ModelsCommand implements Command {

    private final ModelService modelService;

    public ModelsCommand(ModelService modelService) {
        this.modelService = modelService;
    }

    @Override
    public String getName() {
        return "models";
    }

    @Override
    public String getDescription() {
        return "查看可用 AI 模型 & API Key 申请";
    }

    @Override
    public String getUsage() {
        return "models";
    }

    @Override
    public void execute(String[] args) throws CLIException {
        modelService.printModelList();
    }
}
