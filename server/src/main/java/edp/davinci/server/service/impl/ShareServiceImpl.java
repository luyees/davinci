/*
 * <<
 *  Davinci
 *  ==
 *  Copyright (C) 2016 - 2020 EDP
 *  ==
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *        http://www.apache.org/licenses/LICENSE-2.0
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  >>
 *
 */

package edp.davinci.server.service.impl;

import edp.davinci.commons.util.AESUtils;
import edp.davinci.commons.util.StringUtils;
import edp.davinci.core.dao.entity.*;
import edp.davinci.server.aspect.ShareAuthAspect;
import edp.davinci.server.commons.Constants;
import edp.davinci.server.commons.ErrorMsg;
import edp.davinci.server.dao.*;
import edp.davinci.server.dto.display.MemDisplaySlideWidgetWithSlide;
import edp.davinci.server.dto.project.ProjectDetail;
import edp.davinci.server.dto.project.ProjectPermission;
import edp.davinci.server.dto.share.*;
import edp.davinci.server.dto.user.UserLogin;
import edp.davinci.server.dto.view.WidgetDistinctParam;
import edp.davinci.server.dto.view.WidgetQueryParam;
import edp.davinci.server.dto.view.ViewWithProjectAndSource;
import edp.davinci.server.enums.ShareDataPermission;
import edp.davinci.server.enums.ShareMode;
import edp.davinci.server.exception.ForbiddenExecption;
import edp.davinci.server.exception.NotFoundException;
import edp.davinci.server.exception.ServerException;
import edp.davinci.server.exception.UnAuthorizedExecption;
import edp.davinci.server.model.*;
import edp.davinci.server.service.ProjectService;
import edp.davinci.server.service.ShareService;
import edp.davinci.server.service.UserService;
import edp.davinci.server.service.ViewService;
import edp.davinci.commons.util.CollectionUtils;
import edp.davinci.server.util.ServerUtils;
import edp.davinci.server.util.TokenUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;

import static edp.davinci.commons.Constants.*;

import java.io.File;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;


@Service
@Slf4j
public class ShareServiceImpl implements ShareService {

    @Autowired
    private TokenUtils tokenUtils;

    @Autowired
    private UserExtendMapper userExtendMapper;

    @Autowired
    private WidgetExtendMapper widgetMapper;

    @Autowired
    private DisplayExtendMapper displayExtendMapper;

    @Autowired
    private DisplaySlideExtendMapper displaySlideExtendMapper;

    @Autowired
    private DashboardExtendMapper dashboardExtendMapper;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ViewExtendMapper viewExtendMapper;

    @Autowired
    private ViewService viewService;

    @Autowired
    private MemDisplaySlideWidgetExtendMapper memDisplaySlideWidgetExtendMapper;

    @Autowired
    private MemDashboardWidgetExtendMapper memDashboardWidgetExtendMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private RoleExtendMapper roleExtendMapper;

    @Autowired
    private ServerUtils serverUtils;

    @Autowired
    private ProjectExtendMapper projectExtendMapper;

    @Autowired
    private RelRoleUserExtendMapper relRoleUserExtendMapper;

    @Autowired
    private String TOKEN_SECRET;

    @Override
    public User shareLogin(UserLogin userLogin) throws NotFoundException, ServerException, UnAuthorizedExecption {
        ShareFactor shareFactor = ShareAuthAspect.SHARE_FACTOR_THREAD_LOCAL.get();
        User loginUser = userService.userLogin(userLogin);
        if (null == loginUser) {
            throw new NotFoundException("User is not found");
        }
        if (shareFactor.getPermission() == ShareDataPermission.SHARER) {
            if (!loginUser.getId().equals(shareFactor.getSharerId())) {
                throw new ForbiddenExecption(ErrorMsg.ERR_PERMISSION);
            }
        } else {
            Set<RelRoleUser> relRoleUsers = relRoleUserExtendMapper.getByUserAndRoles(loginUser.getId(), shareFactor.getRoles());
            if (!shareFactor.getViewers().contains(loginUser.getId()) && CollectionUtils.isEmpty(relRoleUsers)) {
                throw new ForbiddenExecption(ErrorMsg.ERR_PERMISSION);
            }
        }
        //是否激活
        if (!loginUser.getActive()) {
            throw new ServerException("This user is not active");
        }
        return loginUser;
    }

    /**
     * 获取分享widget
     *
     * @param user
     * @return
     */
    @Override
    public ShareWidget getShareWidget(User user) throws NotFoundException, ServerException, ForbiddenExecption, UnAuthorizedExecption {

        ShareFactor shareFactor = ShareAuthAspect.SHARE_FACTOR_THREAD_LOCAL.get();
        Widget widget = (Widget) shareFactor.getShareEntity();
        ShareWidget shareWidget = widgetMapper.getShareWidgetById(widget.getId());

        if (null == shareWidget) {
            throw new NotFoundException("Widget not found");
        }

        shareFactor.freshShareDataToken(shareWidget, TOKEN_SECRET);
        return shareWidget;
    }


    /**
     * 获取分享Display
     *
     * @param user
     * @return
     */
    @Override
    public ShareDisplay getShareDisplay(User user) throws NotFoundException, ServerException, ForbiddenExecption, UnAuthorizedExecption {
        ShareFactor shareFactor = ShareAuthAspect.SHARE_FACTOR_THREAD_LOCAL.get();
        Display display = (Display) shareFactor.getShareEntity();
        ShareDisplay shareDisplay = new ShareDisplay();

        BeanUtils.copyProperties(display, shareDisplay);

        List<MemDisplaySlideWidgetWithSlide> memWithSlides = memDisplaySlideWidgetExtendMapper.getMemWithSlideByDisplayId(display.getId());
        List<DisplaySlide> displaySlides = displaySlideExtendMapper.selectByDisplayId(display.getId());
        Set<MemDisplaySlideWidget> memDisplaySlideWidgetSet = null;

        if (!CollectionUtils.isEmpty(memWithSlides)) {
            memDisplaySlideWidgetSet = new HashSet<>();
            for (MemDisplaySlideWidgetWithSlide memWithSlide : memWithSlides) {
                MemDisplaySlideWidget memDisplaySlideWidget = new MemDisplaySlideWidget();
                BeanUtils.copyProperties(memWithSlide, memDisplaySlideWidget);
                memDisplaySlideWidgetSet.add(memDisplaySlideWidget);
            }
        }

        if (!CollectionUtils.isEmpty(displaySlides)) {
            Set<ShareDisplaySlide> shareDisplaySlideSet = new HashSet<>();
            for (DisplaySlide displaySlide : displaySlides) {
                ShareDisplaySlide shareDisplaySlide = new ShareDisplaySlide();
                BeanUtils.copyProperties(displaySlide, shareDisplaySlide);

                if (!CollectionUtils.isEmpty(memDisplaySlideWidgetSet)) {
                    Iterator<MemDisplaySlideWidget> memIterator = memDisplaySlideWidgetSet.iterator();
                    Set<MemDisplaySlideWidget> relations = new HashSet<>();
                    while (memIterator.hasNext()) {
                        MemDisplaySlideWidget memDisplaySlideWidget = memIterator.next();
                        if (memDisplaySlideWidget.getDisplaySlideId().equals(displaySlide.getId())) {
                            relations.add(memDisplaySlideWidget);
                        }
                    }
                    shareDisplaySlide.setRelations(relations);
                }
                shareDisplaySlideSet.add(shareDisplaySlide);
            }
            shareDisplay.setSlides(shareDisplaySlideSet);
        }

        Set<ShareWidget> shareWidgets = widgetMapper.getShareWidgetsByDisplayId(display.getId());
        if (!CollectionUtils.isEmpty(shareWidgets)) {
            shareWidgets.forEach(shareWidget -> shareFactor.freshShareDataToken(shareWidget, TOKEN_SECRET));
            shareDisplay.setWidgets(shareWidgets);
        }

        return shareDisplay;
    }

    /**
     * 获取分享dashboard
     *
     * @param user
     * @return
     */
    @Override
    @Transactional
    public ShareDashboard getShareDashboard(User user) throws NotFoundException, ServerException, ForbiddenExecption, UnAuthorizedExecption {
        ShareFactor shareFactor = ShareAuthAspect.SHARE_FACTOR_THREAD_LOCAL.get();
        Dashboard dashboard = (Dashboard) shareFactor.getShareEntity();
        ShareDashboard shareDashboard = new ShareDashboard();
        BeanUtils.copyProperties(dashboard, shareDashboard);

        List<MemDashboardWidget> memDashboardWidgets = memDashboardWidgetExtendMapper.getByDashboardId(dashboard.getId());
        shareDashboard.setRelations(memDashboardWidgets);

        Set<ShareWidget> shareWidgets = widgetMapper.getShareWidgetsByDashboard(dashboard.getId());
        if (!CollectionUtils.isEmpty(shareWidgets)) {
            shareWidgets.forEach(shareWidget -> shareFactor.freshShareDataToken(shareWidget, TOKEN_SECRET));
        }
        shareDashboard.setWidgets(shareWidgets);
        return shareDashboard;
    }


    /**
     * 获取分享数据
     *
     * @param queryParam
     * @param user
     * @return
     */
    @Override
    public Paging<Map<String, Object>> getShareData(WidgetQueryParam queryParam, User user)
            throws NotFoundException, ServerException, ForbiddenExecption, UnAuthorizedExecption, SQLException {
        ShareFactor shareFactor = ShareAuthAspect.SHARE_FACTOR_THREAD_LOCAL.get();
        Widget widget = (Widget) shareFactor.getShareEntity();

        ViewWithProjectAndSource viewWithProjectAndSource = viewExtendMapper.getViewWithProjectAndSourceByWidgetId(widget.getId());

        ProjectDetail projectDetail = projectService.getProjectDetail(viewWithProjectAndSource.getProjectId(), shareFactor.getUser(), false);
        boolean maintainer = projectService.isMaintainer(projectDetail, shareFactor.getUser());

        Paging paging = viewService.getDataWithQueryColumns(maintainer, viewWithProjectAndSource, queryParam, shareFactor.getUser());
        return paging;
    }

    /**
     * 获取分享distinct value
     *
     * @param viewId
     * @param param
     * @param user
     * @return
     */
    @Override
    public List<Map<String, Object>> getDistinctValue(Long viewId, WidgetDistinctParam param, User user) {
        List<Map<String, Object>> list = null;
        ShareFactor shareFactor = ShareAuthAspect.SHARE_FACTOR_THREAD_LOCAL.get();

        ViewWithProjectAndSource viewWithProjectAndSource = viewExtendMapper.getViewWithProjectAndSourceById(viewId);
        if (null == viewWithProjectAndSource) {
            log.info("View({}) not found", viewId);
            throw new NotFoundException("view is not found");
        }

        ProjectDetail projectDetail = projectService.getProjectDetail(viewWithProjectAndSource.getProjectId(), shareFactor.getUser(), false);

        if (!projectService.allowGetData(projectDetail, shareFactor.getUser())) {
            throw new UnAuthorizedExecption(ErrorMsg.ERR_PERMISSION);
        }

        try {
            boolean maintainer = projectService.isMaintainer(projectDetail, shareFactor.getUser());
            list = viewService.getDistinctValue(maintainer, viewWithProjectAndSource, param, shareFactor.getUser());
        } catch (ServerException e) {
            throw new UnAuthorizedExecption(e.getMessage());
        }

        return list;
    }


    public void formatShareParam(Long projectId, ShareEntity entity) {
        if (entity.getMode() != ShareMode.AUTH) {
            return;
        }

        Set<Long> viewers = new HashSet<>();
        Set<Long> roleIds = new HashSet<>();

        if (!CollectionUtils.isEmpty(entity.getViewers())) {
            List<User> users = userExtendMapper.getByIds(new ArrayList<>(entity.getViewers()));
            users.stream().map(User::getId).forEach(viewers::add);
        }

        if (!CollectionUtils.isEmpty(entity.getRoles())) {
            Project project = projectExtendMapper.selectByPrimaryKey(projectId);
            List<Role> roles = roleExtendMapper.getByOrgIdAndIds(project.getOrgId(), new ArrayList<>(entity.getRoles()));
            roles.stream().map(Role::getId).forEach(roleIds::add);
        }

        entity.setViewers(viewers);
        entity.setRoles(roleIds);
    }

    /**
     * 获取登录用户权限
     *
     * @return
     * @throws ServerException
     * @throws ForbiddenExecption
     */
    @Override
    public Map<String, Object> getSharePermissions() throws ServerException, ForbiddenExecption {
        Map<String, Object> map = new HashMap<>(1);
        ShareFactor shareFactor = ShareAuthAspect.SHARE_FACTOR_THREAD_LOCAL.get();
        ProjectDetail projectDetail = shareFactor.getProjectDetail();
        if (projectDetail == null) {
            map.put("download", false);
            return map;
        }
        ProjectPermission projectPermission = projectService.getProjectPermission(projectDetail, shareFactor.getUser());
        if (projectPermission == null) {
            map.put("download", false);
            return map;
        }
        map.put("download", projectPermission.getDownloadPermission());
        return map;
    }

    /**
     * 前置接口：获取分享模式
     *
     * @return
     * @throws ServerException
     * @throws ForbiddenExecption
     */
    @Override
    public Map<String, Object> checkShareToken() throws ServerException, ForbiddenExecption {
        ShareFactor shareFactor = ShareAuthAspect.SHARE_FACTOR_THREAD_LOCAL.get();
        Map<String, Object> map = new HashMap<>(1);
        map.put("type", shareFactor.getMode().name());
        return map;
    }

    /**
     * 获取分享实体id
     * 已废弃，仅供版本兼容
     *
     * @param token
     * @param user
     * @return
     * @throws ServerException
     * @throws UnAuthorizedExecption
     */
    @Deprecated
    public ShareInfo getShareInfo(String token, User user) throws ServerException, ForbiddenExecption {

        if (StringUtils.isEmpty(token)) {
            throw new ServerException(ErrorMsg.ERR_INVALID_TOKEN);
        }

        //AES解密
        String decrypt = AESUtils.decrypt(token, null);
        //获取分享信息
        String tokenUserName = tokenUtils.getUsername(decrypt);
        String tokenPassword = tokenUtils.getPassword(decrypt);

        String[] tokenInfos = tokenUserName.split(Constants.SPLIT_CHAR_STRING);
        String[] tokenCrypts = tokenPassword.split(Constants.SPLIT_CHAR_STRING);

        if (tokenInfos.length < 2) {
            throw new ServerException(ErrorMsg.ERR_INVALID_TOKEN);
        }

        Long shareUserId = Long.parseLong(tokenInfos[1]);
        if (shareUserId < 1L) {
            throw new ServerException(ErrorMsg.ERR_INVALID_TOKEN);
        }

        User shareUser = userExtendMapper.selectByPrimaryKey(shareUserId);
        if (null == shareUser) {
            throw new ServerException(ErrorMsg.ERR_INVALID_TOKEN);
        }

        String sharedUserName = null;
        if (tokenInfos.length == 3) {
            if (tokenCrypts.length < 2) {
                throw new ServerException(ErrorMsg.ERR_INVALID_TOKEN);
            }
            String username = tokenInfos[2];
            Long sharedUserId = Long.parseLong(tokenCrypts[1]);
            User sharedUser = userExtendMapper.selectByUsername(username);
            if (null == sharedUser || !sharedUser.getId().equals(sharedUserId)) {
                throw new ForbiddenExecption(ErrorMsg.ERR_AUTHENTICATION);
            }

            if (null == user || (!user.getId().equals(sharedUserId) && !user.getId().equals(shareUserId))) {
                throw new ForbiddenExecption(ErrorMsg.ERR_AUTHENTICATION);
            }

            sharedUserName = username;
        }

        Long shareId1 = Long.parseLong(tokenInfos[0]);
        Long shareId2 = Long.parseLong(tokenCrypts[0]);

        if (shareId1 < 1L || shareId2 < 1L || !shareId1.equals(shareId2)) {
            throw new ServerException(ErrorMsg.ERR_INVALID_TOKEN);
        }

        return new ShareInfo(shareId1, shareUser, sharedUserName);
    }


    /**
     * 验证分享信息
     * 已废弃，仅供版本兼容
     *
     * @param user
     * @param shareInfo
     * @throws ServerException
     * @throws ForbiddenExecption
     */
    @Deprecated
    public void verifyShareUser(User user, ShareInfo shareInfo) throws ServerException, ForbiddenExecption {
        if (null == shareInfo || shareInfo.getShareId() < 1L) {
            throw new ServerException(ErrorMsg.ERR_INVALID_TOKEN);
        }

        if (!StringUtils.isEmpty(shareInfo.getSharedUserName())) {
            User tokenUser = userExtendMapper.selectByUsername(shareInfo.getSharedUserName());
            if (tokenUser == null || !tokenUser.getId().equals(user.getId())) {
                throw new ForbiddenExecption(ErrorMsg.ERR_PERMISSION);
            }
        }
    }

    /**
     * 生成分享token
     * 已废弃，仅供版本兼容
     *
     * @param shareEntityId
     * @param username
     * @return
     * @throws ServerException
     */
    @Override
    @Deprecated
    public String generateShareToken(Long shareEntityId, String username, Long userId) throws ServerException {
        /**
         * username: share实体Id:-:分享人id[:-:被分享人用户名]
         * password: share实体Id[:-:被分享人Id]
         */
        TokenEntity shareToken = new TokenEntity();
        String tokenUserName = shareEntityId + Constants.SPLIT_CHAR_STRING + userId;
        String tokenPassword = shareEntityId + EMPTY;
        if (!StringUtils.isEmpty(username)) {
            User shareUser = userExtendMapper.selectByUsername(username);
            if (null == shareUser) {
                throw new ServerException("User " + username + " not found");
            }
            tokenUserName += Constants.SPLIT_CHAR_STRING + username;
            tokenPassword += (Constants.SPLIT_CHAR_STRING + shareUser.getId());
        }
        shareToken.setUsername(tokenUserName);
        shareToken.setPassword(tokenPassword);

        //生成token 并 aes加密
        return AESUtils.encrypt(tokenUtils.generateContinuousToken(shareToken), null);
    }
}