package org.jeecg.modules.activiti.web;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.slf4j.Slf4j;
import org.activiti.bpmn.converter.BpmnXMLConverter;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.editor.constants.ModelDataJsonConstants;
import org.activiti.editor.language.json.converter.BpmnJsonConverter;
import org.activiti.engine.*;
import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.repository.Model;
import org.activiti.engine.repository.ModelQuery;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.activiti.image.ProcessDiagramGenerator;
import org.activiti.image.impl.DefaultProcessDiagramGenerator;
import org.apache.commons.io.IOUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.modules.activiti.entity.ActZprocess;
import org.jeecg.modules.activiti.service.IActModelService;
import org.jeecg.modules.activiti.service.Impl.ActZprocessServiceImpl;
import org.jeecg.modules.activiti.vo.ProcessDeploymentVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ????????????
 *
 * @author: dongjb
 * @date: 2021/5/26
 */
@RestController
@RequestMapping("/activiti/models")
@Slf4j
@Api(tags = "?????????-????????????", value = "????????????Model????????????")
public class ActivitiModelController {
    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final RuntimeService runtimeService;
    private final ProcessEngineConfiguration processEngineConfiguration;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final ActZprocessServiceImpl actZprocessService;
    private final IActModelService iActModelService;

    @Autowired
    public ActivitiModelController(RepositoryService repositoryService,
                                   HistoryService historyService,
                                   RuntimeService runtimeService,
                                   ProcessEngineConfiguration processEngineConfiguration,
                                   TaskService taskService,
                                   ObjectMapper objectMapper,
                                   ActZprocessServiceImpl actZprocessService,
                                   IActModelService iActModelService) {
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.processEngineConfiguration = processEngineConfiguration;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.actZprocessService = actZprocessService;
        this.iActModelService = iActModelService;
    }

    public static final String NC_NAME = "NCName";
    public static final String PRIMARY = "PRIMARY";

    public static final String BPMN_SUFFIX = ".bpmn";
    public static final String BPMN_XML_SUFFIX = ".bpmn20.xml";
    public static final String JSON_SUFFIX = ".json";
    public static final String FILE_TYPE_JSON = "json";
    public static final String FILE_TYPE_BPMN = "bpmn";
    public static final int READ_LENGTH = 1024;

    /**
     * ??????????????????
     *
     * @param request keyWord ???????????? like
     * @return ???????????????????????????
     */
    @AutoLog(value = "??????????????????")
    @ApiOperation(value = "????????????", notes = "keyWord?????????????????????????????????????????????????????????ACT_RE_MODEL")
    @RequestMapping(value = "/modelListData", method = RequestMethod.GET)
    @ResponseBody
    public Result<Object> modelListData(HttpServletRequest request) {
        log.info("-------------????????????-------------");
        ModelQuery modelQuery = repositoryService.createModelQuery();
        //???????????????
        String keyWord = request.getParameter("keyWord");
        if (StrUtil.isNotBlank(keyWord)) {
            modelQuery.modelNameLike("%" + keyWord + "%");
        }
        List<Model> models = modelQuery.orderByCreateTime().desc().list();

        return Result.OK(models);
    }

    /**
     * ????????????
     *
     * @param request  http ??????
     * @param response http ??????
     */
    @AutoLog(value = "????????????")
    @ApiOperation(value = "????????????", notes = "?????????????????????ACT_RE_MODEL?????????ACT_GE_BYTEARRAY???name=source,deployment=null???")
    @RequestMapping(value = "/create", method = RequestMethod.GET)
    public void newModel(HttpServletRequest request, HttpServletResponse response) {
        try {
            //????????????????????????
            Model model = repositoryService.newModel();
            //????????????????????????
            int revision = 1;
            String name = request.getParameter("name");
            String description = request.getParameter("description");
            String key = request.getParameter("key");
            if (StrUtil.isBlank(name)) {
                name = "new-process";
            }
            if (StrUtil.isBlank(description)) {
                description = "description";
            }
            if (StrUtil.isBlank(key)) {
                key = "processKey";
            }

            ObjectNode modelNode = objectMapper.createObjectNode();
            modelNode.put(ModelDataJsonConstants.MODEL_NAME, name);
            modelNode.put(ModelDataJsonConstants.MODEL_DESCRIPTION, description);
            modelNode.put(ModelDataJsonConstants.MODEL_REVISION, revision);

            model.setName(name);
            model.setKey(key);
            model.setMetaInfo(modelNode.toString());

            repositoryService.saveModel(model);
            String id = model.getId();

            //??????ModelEditorSource
            ObjectNode editorNode = objectMapper.createObjectNode();
            editorNode.put("id", "canvas");
            editorNode.put("resourceId", "canvas");
            ObjectNode stencilSetNode = objectMapper.createObjectNode();
            stencilSetNode.put("namespace", "http://b3mn.org/stencilset/bpmn2.0#");
            editorNode.put("stencilset", stencilSetNode);
            repositoryService.addModelEditorSource(id, editorNode.toString().getBytes(StandardCharsets.UTF_8));
            response.sendRedirect(request.getContextPath() + "/activiti/modeler.html?modelId=" + id);
        } catch (IOException e) {
            e.printStackTrace();
            log.info("?????????????????????");
        }
    }

    @AutoLog(value = "????????????????????????")
    @ApiOperation(value = "????????????", notes = "???????????????????????????bpmn???xml??????.??????ACT_RE_MODEL?????????ACT_GE_BYTEARRAY???name=source,deployment=null???")
    @RequestMapping(value = "/uploadFile", method = RequestMethod.POST)
    public Result<Object> deployUploadedFile(@ApiParam(value = "???????????????") @RequestParam("uploadFile") MultipartFile uploadFile) {
        InputStreamReader in;
        if (uploadFile == null) {
            return Result.error("????????????????????????");
        }
        String fileName = uploadFile.getOriginalFilename();
        if (fileName == null) {
            return Result.error("?????????????????????");
        }
        if (!(fileName.endsWith(BPMN_XML_SUFFIX) || fileName.endsWith(BPMN_SUFFIX))) {
            return Result.error("??????????????????");
        }

        try {
            in = new InputStreamReader(new ByteArrayInputStream(uploadFile.getBytes()), StandardCharsets.UTF_8);
            iActModelService.createModel(in, null);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return Result.OK("??????????????????");
    }


    /**
     * ????????????
     *
     * @param id ??????id
     * @return ??????????????????
     */
    @AutoLog(value = "????????????")
    @ApiOperation(value = "????????????", notes = "??????????????????????????????, ??????ACT_RE_MODEL?????????ACT_GE_BYTEARRAY???name=source,deployment=null???")
    @RequestMapping(value = "/delete/{id}", method = RequestMethod.GET)
    public @ResponseBody
    Result<Object> deleteModel(@ApiParam(value = "??????id") @PathVariable("id") String id) {
        repositoryService.deleteModel(id);
        return Result.OK("???????????????");
    }

    /**
     * ????????????
     *
     * @param id ??????id
     * @return ??????????????????
     */
    @AutoLog(value = "????????????")
    @ApiOperation(value = "????????????", notes = "???????????????????????????,??????ACT_RE_PROCDEF???ACT_RE_DEPLOYMENT???ACT_Z_PROCESS???ACT_GE_BYTEARRAY???deployment 2?????????????????????id???????????????")
    @RequestMapping(value = "/deployment/{id}", method = RequestMethod.GET)
    public @ResponseBody
    Result<Object> deploy(@ApiParam(value = "??????id") @PathVariable("id") String id) {

        // ????????????
        Model modelData = repositoryService.getModel(id);
        byte[] bytes = repositoryService.getModelEditorSource(modelData.getId());

        if (bytes == null) {
            return Result.error("????????????????????????????????????????????????????????????????????????");
        }

        try {
            JsonNode modelNode = new ObjectMapper().readTree(bytes);

            BpmnModel model = new BpmnJsonConverter().convertToBpmnModel(modelNode);
            if (model.getProcesses().size() == 0) {
                return Result.error("??????????????????????????????????????????????????????");
            }
            byte[] bpmnBytes = new BpmnXMLConverter().convertToXML(model);

            // ????????????????????????
            String processName = modelData.getName() + BPMN_XML_SUFFIX;
            Deployment deployment = repositoryService.createDeployment()
                    .name(modelData.getName())
                    .addString(processName, new String(bpmnBytes, StandardCharsets.UTF_8))
                    .deploy();

            //???????????? model ?????? deployment id ?????? ??????????????????????????????
            modelData.setDeploymentId(deployment.getId());
            repositoryService.saveModel(modelData);

            String metaInfo = modelData.getMetaInfo();
            JSONObject metaInfoMap = JSON.parseObject(metaInfo);
            // ?????????????????? ??????????????????????????????
            List<ProcessDefinition> list = repositoryService.createProcessDefinitionQuery().deploymentId(deployment.getId()).list();
            for (ProcessDefinition pd : list) {
                ActZprocess actZprocess = new ActZprocess();
                actZprocess.setId(pd.getId());
                actZprocess.setName(modelData.getName());
                actZprocess.setProcessKey(modelData.getKey());
                actZprocess.setDeploymentId(deployment.getId());
                actZprocess.setDescription(metaInfoMap.getString(ModelDataJsonConstants.MODEL_DESCRIPTION));
                actZprocess.setVersion(pd.getVersion());
                actZprocess.setDiagramName(pd.getDiagramResourceName());
                actZprocessService.setAllOldByProcessKey(modelData.getKey());
                actZprocess.setLatest(true);
                actZprocessService.save(actZprocess);
            }
        } catch (Exception e) {
            String err = e.toString();
            log.error(e.getMessage(), e);
            if (err.contains(NC_NAME)) {
                return Result.error("??????????????????????????????????????????????????????????????????????????????????????????????????????");
            }
            if (err.contains(PRIMARY)) {
                return Result.error("????????????????????????????????????key?????????");
            }
            return Result.error("???????????????");
        }

        return Result.OK("????????????");
    }

    @AutoLog(value = "???????????????????????????")
    @ApiOperation(value = "???????????????????????????", notes = "??????????????????,???????????????????????????????????????")
    @RequestMapping(value = "/createanddeployment", method = RequestMethod.POST)
    public @ResponseBody
    Result<Object> createanddeployment(@RequestBody ProcessDeploymentVo deployment) {
        try {
            InputStreamReader in = new InputStreamReader(new ByteArrayInputStream(deployment.getXml().getBytes()), StandardCharsets.UTF_8);
            Result<Object> modelId = iActModelService.createModel(in, deployment);
            if(modelId.isSuccess()) {
                iActModelService.deployProcess(modelId, deployment);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Result.OK("????????????????????????");
    }

    /**
     * ??????????????????
     */
    @AutoLog(value = "??????????????????")
    @ApiOperation(value = "??????????????????", notes = "????????????????????????????????????xml????????????json??????")
    @RequestMapping(value = "/activiti/export/{modelId}/{type}", method = RequestMethod.GET)
    @ResponseBody
    public void export(@ApiParam(value = "??????id??????deploymentId") @PathVariable("modelId") String modelId,
                       @ApiParam(value = "???????????? json???bpmn") @PathVariable("type") String type,
                       HttpServletResponse response) throws IOException {
        try {
            String id = null;
            Model modelData = repositoryService.getModel(modelId);
            if(ObjectUtil.isNotNull(modelData) && StrUtil.isNotBlank(modelData.getId())) {
                id = modelData.getId();
            }
            if (ObjectUtil.isNull(modelData) || StrUtil.isBlank(modelData.getId())) {
                Model model = repositoryService.createModelQuery()
                        .deploymentId(modelId).singleResult();
                id = model.getId();
            }

            BpmnJsonConverter jsonConverter = new BpmnJsonConverter();
            byte[] modelEditorSource = repositoryService.getModelEditorSource(id);
            JsonNode editorNode = new ObjectMapper().readTree(modelEditorSource);
            BpmnModel bpmnModel = jsonConverter.convertToBpmnModel(editorNode);

            // ????????????
            if (bpmnModel.getMainProcess() == null) {
                response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
                response.getOutputStream().println("???????????????, ????????????????????????: " + type);
                response.flushBuffer();
                return;
            }

            String filename = "";
            byte[] exportBytes = null;
            String mainProcessId = bpmnModel.getMainProcess().getId();
            if (type.equals(FILE_TYPE_BPMN)) {
                BpmnXMLConverter xmlConverter = new BpmnXMLConverter();
                exportBytes = xmlConverter.convertToXML(bpmnModel);
                filename = mainProcessId + BPMN_XML_SUFFIX;
            } else if (type.equals(FILE_TYPE_JSON)) {
                exportBytes = modelEditorSource;
                filename = mainProcessId + JSON_SUFFIX;
            }

            // ????????????
            if (exportBytes == null) {
                response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
                response.getOutputStream().println("???????????????, ????????????????????????: " + type);
                response.flushBuffer();
                return;
            }

            ByteArrayInputStream in = new ByteArrayInputStream(exportBytes);
            response.setHeader("Content-Disposition", "attachment; filename=" + new String(filename.getBytes("gb2312"), "ISO8859-1"));
            IOUtils.copy(in, response.getOutputStream());
            response.flushBuffer();
        } catch (Exception e) {
            log.info("???????????????modelId=" + modelId, e);
        }
    }

    /**
     * ??????????????????
     */
    @AutoLog(value = "??????????????????")
    @ApiOperation(value = "??????????????????", notes = "?????????????????????")
    @RequestMapping(value = "/activiti/exportDiagram", method = RequestMethod.GET)
    public void showModelPicture(@ApiParam(value = "??????id") String modelId, HttpServletResponse response) throws Exception {
        Model modelData = repositoryService.getModel(modelId);
        ObjectNode modelNode = null;
        try {
            modelNode = (ObjectNode) new ObjectMapper().readTree(repositoryService.getModelEditorSource(modelData.getId()));
        } catch (IOException e) {
            e.printStackTrace();
        }
        BpmnModel model = new BpmnJsonConverter().convertToBpmnModel(modelNode);
        ProcessDiagramGenerator processDiagramGenerator = new DefaultProcessDiagramGenerator();
        InputStream inputStream = processDiagramGenerator.generatePngDiagram(model);

        String filename = model.getMainProcess().getId() + ".png";
        response.setHeader("Content-Disposition", "inline; filename=" + new String(filename.getBytes("gb2312"), "ISO8859-1"));

        OutputStream out = response.getOutputStream();
        for (int b; (b = inputStream.read()) != -1; ) {
            out.write(b);
        }
        out.close();
        inputStream.close();
    }

    /**
     * ????????????????????????
     */
    @AutoLog(value = "????????????????????????")
    @ApiOperation(value = "????????????????????????", notes = "????????????????????????, ?????????????????????????????????")
    @RequestMapping(value = "/export", method = RequestMethod.GET)
    public void exportResource(@ApiParam(value = "????????????id") @RequestParam String id, HttpServletResponse response) {

        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(id).singleResult();

        String resourceName = pd.getDiagramResourceName();
        InputStream inputStream = repositoryService.getResourceAsStream(pd.getDeploymentId(), resourceName);

        try {
            contentOutput(response, resourceName, inputStream);
        } catch (IOException e) {
            log.error(e.toString());
        }
    }

    /**
     * ????????????????????????
     *
     * @param id       ????????????
     * @param response http??????
     */
    @AutoLog(value = "????????????????????????")
    @ApiOperation(value = "????????????????????????", notes = "????????????????????????")
    @RequestMapping(value = "/getHighlightImg/{id}", method = RequestMethod.GET)
    public void getHighlightImg(@ApiParam(value = "????????????id") @PathVariable String id, HttpServletResponse response) {
        InputStream inputStream;
        ProcessInstance pi;
        String picName;
        // ????????????
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult();
        if (hpi.getEndTime() != null) {
            // ??????????????????????????????
            ProcessDefinition pd = repositoryService.createProcessDefinitionQuery().processDefinitionId(hpi.getProcessDefinitionId()).singleResult();
            picName = pd.getDiagramResourceName();
            inputStream = repositoryService.getResourceAsStream(pd.getDeploymentId(), pd.getDiagramResourceName());
        } else {
            pi = runtimeService.createProcessInstanceQuery().processInstanceId(id).singleResult();
            BpmnModel bpmnModel = repositoryService.getBpmnModel(pi.getProcessDefinitionId());

            List<String> highLightedActivities = new ArrayList<>();
            // ??????????????????
            List<Task> tasks = taskService.createTaskQuery().processInstanceId(id).list();
            for (Task task : tasks) {
                highLightedActivities.add(task.getTaskDefinitionKey());
            }

            List<String> highLightedFlows = new ArrayList<>();
            ProcessDiagramGenerator diagramGenerator = processEngineConfiguration.getProcessDiagramGenerator();
            //"??????"
            inputStream = diagramGenerator.generateDiagram(bpmnModel, "png", highLightedActivities, highLightedFlows,
                    "??????", "??????", "??????", null, 1.0);
            picName = pi.getName() + ".png";
        }
        try {
            contentOutput(response, picName, inputStream);
        } catch (IOException e) {
            log.error(e.toString());
            throw new JeecgBootException("????????????????????????");
        }
    }

    /**
     * @param response     http ????????????
     * @param resourceName ????????????
     * @param inputStream  ?????????
     * @throws IOException io??????
     */
    private void contentOutput(HttpServletResponse response, String resourceName, InputStream inputStream) throws IOException {
        response.setContentType("application/octet-stream;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(resourceName, "UTF-8"));
        byte[] b = new byte[1024];
        int len;
        while ((len = inputStream.read(b, 0, READ_LENGTH)) != -1) {
            response.getOutputStream().write(b, 0, len);
        }
        response.flushBuffer();
    }
}
