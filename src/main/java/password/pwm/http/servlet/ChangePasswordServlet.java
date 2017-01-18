/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2016 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.http.servlet;

import com.novell.ldapchai.ChaiUser;
import com.novell.ldapchai.exception.ChaiException;
import com.novell.ldapchai.exception.ChaiOperationException;
import com.novell.ldapchai.exception.ChaiUnavailableException;
import password.pwm.Permission;
import password.pwm.PwmApplication;
import password.pwm.PwmConstants;
import password.pwm.bean.EmailItemBean;
import password.pwm.bean.LocalSessionStateBean;
import password.pwm.bean.LoginInfoBean;
import password.pwm.bean.PasswordStatus;
import password.pwm.bean.UserInfoBean;
import password.pwm.config.Configuration;
import password.pwm.config.FormConfiguration;
import password.pwm.config.FormUtility;
import password.pwm.config.PwmSetting;
import password.pwm.config.option.RequireCurrentPasswordMode;
import password.pwm.config.profile.PwmPasswordRule;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmDataValidationException;
import password.pwm.error.PwmError;
import password.pwm.error.PwmOperationalException;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.http.HttpMethod;
import password.pwm.http.JspUrl;
import password.pwm.http.PwmRequest;
import password.pwm.http.PwmSession;
import password.pwm.http.bean.ChangePasswordBean;
import password.pwm.http.ProcessStatus;
import password.pwm.i18n.Message;
import password.pwm.ldap.PasswordChangeProgressChecker;
import password.pwm.ldap.auth.AuthenticationType;
import password.pwm.svc.event.AuditEvent;
import password.pwm.svc.event.AuditRecord;
import password.pwm.svc.event.AuditRecordFactory;
import password.pwm.svc.stats.Statistic;
import password.pwm.util.PasswordData;
import password.pwm.util.PwmPasswordRuleValidator;
import password.pwm.util.java.JsonUtil;
import password.pwm.util.java.TimeDuration;
import password.pwm.util.logging.PwmLogger;
import password.pwm.util.macro.MacroMachine;
import password.pwm.util.operations.PasswordUtility;
import password.pwm.ws.server.RestResultBean;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * User interaction servlet for changing (self) passwords.
 *
 * @author Jason D. Rivard.
 */

@WebServlet(
        name="ChangePasswordServlet",
        urlPatterns={
                PwmConstants.URL_PREFIX_PRIVATE + "/changepassword",
                PwmConstants.URL_PREFIX_PUBLIC + "/changepassword",
                PwmConstants.URL_PREFIX_PRIVATE + "/ChangePassword",
                PwmConstants.URL_PREFIX_PUBLIC + "/ChangePassword"
        }
)
public class ChangePasswordServlet extends ControlledPwmServlet {

    private static final PwmLogger LOGGER = PwmLogger.forClass(ChangePasswordServlet.class);

    private enum ChangePasswordAction implements ControlledPwmServlet.ProcessAction {
        checkProgress(HttpMethod.POST, RestCheckProgressHandler.class),
        complete(HttpMethod.GET, CompleteActionHandler.class),
        change(HttpMethod.POST, ChangeRequestHandler.class),
        form(HttpMethod.POST, FormRequestHandler.class),
        agree(HttpMethod.POST, AgreeRequestHandler.class),
        warnResponse(HttpMethod.POST, WarnResponseHandler.class),
        reset(HttpMethod.POST, ResetResponseHandler.class),

        ;

        private final HttpMethod method;
        private final Class<? extends ProcessActionHandler> handlerClass;


        ChangePasswordAction(final HttpMethod method, final Class<? extends ProcessActionHandler> handlerClass)
        {
            this.method = method;
            this.handlerClass = handlerClass;
        }

        public Collection<HttpMethod> permittedMethods()
        {
            return Collections.singletonList(method);
        }

        @Override
        public Class<? extends ProcessActionHandler> getHandlerClass() {
            return handlerClass;
        }
    }

    protected ChangePasswordAction readProcessAction(final PwmRequest request)
            throws PwmUnrecoverableException
    {
        try {
            return ChangePasswordAction.valueOf(request.readParameterAsString(PwmConstants.PARAM_ACTION_REQUEST));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    /*

    public void processAction(final PwmRequest pwmRequest)
            throws ServletException, IOException, ChaiUnavailableException, PwmUnrecoverableException {
        final PwmSession pwmSession = pwmRequest.getPwmSession();
        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();
        final ChangePasswordBean changePasswordBean = pwmApplication.getSessionStateService().getBean(pwmRequest, ChangePasswordBean.class);

        if (pwmSession.getLoginInfoBean().getType() == AuthenticationType.AUTH_WITHOUT_PASSWORD) {
            throw new PwmUnrecoverableException(PwmError.ERROR_PASSWORD_REQUIRED);
        }

        if (!pwmRequest.isAuthenticated()) {
            pwmRequest.respondWithError(PwmError.ERROR_AUTHENTICATION_REQUIRED.toInfo());
            return;
        }

        if (determineIfCurrentPasswordRequired(pwmApplication, pwmSession)) {
            changePasswordBean.setCurrentPasswordRequired(true);
        }

        if (!pwmSession.getSessionManager().checkPermission(pwmApplication, Permission.CHANGE_PASSWORD)) {
            pwmRequest.respondWithError(PwmError.ERROR_UNAUTHORIZED.toInfo());
            return;
        }

        try {
            checkMinimumLifetime(pwmApplication, pwmSession, changePasswordBean, pwmSession.getUserInfoBean());
        } catch (PwmOperationalException e) {
            final ErrorInformation errorInformation = e.getErrorInformation();
            LOGGER.error(pwmSession, errorInformation.toDebugStr());
            pwmRequest.respondWithError(errorInformation);
        }

        final ChangePasswordAction action = readProcessAction(pwmRequest);
        if (action != null) {
            pwmRequest.validatePwmFormID();

            switch (action) {
                case checkProgress:
                    restCheckProgress(pwmRequest, changePasswordBean);
                    return;

                case complete:
                    handleComplete(pwmRequest, changePasswordBean);
                    return;

                case change:
                    handleChangeRequest(pwmRequest, changePasswordBean);
                    break;

                case form:
                    handleFormRequest(pwmRequest, changePasswordBean);
                    break;

                case warnResponse:
                    handleWarnResponseRequest(pwmRequest, changePasswordBean);
                    break;

                case agree:
                    handleAgreeRequest(pwmRequest, changePasswordBean);
                    break;

                case reset:
                    if (pwmSession.getUserInfoBean().isRequiresNewPassword()) {
                        // Must have gotten here from the "Forgotton Password" link.  Better clear out the temporary authentication
                        pwmRequest.getPwmSession().unauthenticateUser(pwmRequest);
                    }

                    pwmRequest.sendRedirect(pwmRequest.getHttpServletRequest().getContextPath());
                    break;

                default:
                    JavaHelper.unhandledSwitchStatement(action);
            }
        }

        if (!pwmRequest.getPwmResponse().isCommitted()) {
            advancedToNextStage(pwmRequest, changePasswordBean);
        }
    }
    */

    class ResetResponseHandler implements ProcessActionHandler {
        @Override
        public ProcessStatus processAction(final PwmRequest pwmRequest) throws ServletException, PwmUnrecoverableException, IOException {

            if (pwmRequest.getPwmSession().getUserInfoBean().isRequiresNewPassword()) {
                // Must have gotten here from the "Forgotten Password" link.  Better clear out the temporary authentication
                pwmRequest.getPwmSession().unauthenticateUser(pwmRequest);
            }

            pwmRequest.sendRedirect(pwmRequest.getHttpServletRequest().getContextPath());

            return ProcessStatus.Halt;
        }
    }

    class WarnResponseHandler implements ProcessActionHandler {
        @Override
        public ProcessStatus processAction(final PwmRequest pwmRequest) throws ServletException, PwmUnrecoverableException, IOException {
            final ChangePasswordBean changePasswordBean = pwmRequest.getPwmApplication().getSessionStateService().getBean(pwmRequest, ChangePasswordBean.class);

            if (pwmRequest.getPwmSession().getUserInfoBean().getPasswordState().isWarnPeriod()) {
                final String warnResponse = pwmRequest.readParameterAsString("warnResponse");
                if ("skip".equalsIgnoreCase(warnResponse)) {
                    pwmRequest.getPwmSession().getLoginInfoBean().setFlag(LoginInfoBean.LoginFlag.skipNewPw);
                    pwmRequest.sendRedirectToContinue();
                } else if ("change".equalsIgnoreCase(warnResponse)) {
                    changePasswordBean.setWarnPassed(true);
                }
            }

            return ProcessStatus.Continue;
        }
    }

    class ChangeRequestHandler implements ProcessActionHandler {
        @Override
        public ProcessStatus processAction(final PwmRequest pwmRequest) throws ServletException, PwmUnrecoverableException, IOException, ChaiUnavailableException {
            final ChangePasswordBean changePasswordBean = pwmRequest.getPwmApplication().getSessionStateService().getBean(pwmRequest, ChangePasswordBean.class);

            final UserInfoBean uiBean = pwmRequest.getPwmSession().getUserInfoBean();

            if (!changePasswordBean.isAllChecksPassed()) {
                return ProcessStatus.Continue;
            }

            final PasswordData password1 = pwmRequest.readParameterAsPassword("password1");
            final PasswordData password2 = pwmRequest.readParameterAsPassword("password2");

            // check the password meets the requirements
            try {
                final ChaiUser theUser = pwmRequest.getPwmSession().getSessionManager().getActor(pwmRequest.getPwmApplication());
                final PwmPasswordRuleValidator pwmPasswordRuleValidator = new PwmPasswordRuleValidator(pwmRequest.getPwmApplication(), uiBean.getPasswordPolicy());
                pwmPasswordRuleValidator.testPassword(password1,null,uiBean,theUser);
            } catch (PwmDataValidationException e) {
                pwmRequest.setResponseError(e.getErrorInformation());
                LOGGER.debug(pwmRequest, "failed password validation check: " + e.getErrorInformation().toDebugStr());
                return ProcessStatus.Continue;
            }

            //make sure the two passwords match
            final boolean caseSensitive = uiBean.getPasswordPolicy().getRuleHelper().readBooleanValue(
                    PwmPasswordRule.CaseSensitive);
            if (PasswordUtility.PasswordCheckInfo.MatchStatus.MATCH != PasswordUtility.figureMatchStatus(caseSensitive,
                    password1, password2)) {
                pwmRequest.setResponseError(PwmError.PASSWORD_DOESNOTMATCH.toInfo());
                pwmRequest.forwardToJsp(JspUrl.PASSWORD_CHANGE);
                return ProcessStatus.Continue;
            }

            try {
                executeChangePassword(pwmRequest, password1);
            } catch (PwmOperationalException e) {
                LOGGER.debug(e.getErrorInformation().toDebugStr());
                pwmRequest.setResponseError(e.getErrorInformation());
                return ProcessStatus.Halt;
            }

            return ProcessStatus.Continue;
        }
    }

    class AgreeRequestHandler implements ProcessActionHandler {
        @Override
        public ProcessStatus processAction(final PwmRequest pwmRequest) throws ServletException, PwmUnrecoverableException, IOException, ChaiUnavailableException {
            final ChangePasswordBean changePasswordBean = pwmRequest.getPwmApplication().getSessionStateService().getBean(pwmRequest, ChangePasswordBean.class);

            LOGGER.debug(pwmRequest, "user accepted password change agreement");
            if (!changePasswordBean.isAgreementPassed()) {
                changePasswordBean.setAgreementPassed(true);
                final AuditRecord auditRecord = new AuditRecordFactory(pwmRequest).createUserAuditRecord(
                        AuditEvent.AGREEMENT_PASSED,
                        pwmRequest.getUserInfoIfLoggedIn(),
                        pwmRequest.getSessionLabel(),
                        "ChangePassword"
                );
                pwmRequest.getPwmApplication().getAuditManager().submit(auditRecord);
            }

            return ProcessStatus.Continue;
        }
    }

    class FormRequestHandler implements ProcessActionHandler {
        @Override
        public ProcessStatus processAction(final PwmRequest pwmRequest)
                throws ServletException, PwmUnrecoverableException, IOException, ChaiUnavailableException
        {
            final ChangePasswordBean cpb = pwmRequest.getPwmApplication().getSessionStateService().getBean(pwmRequest, ChangePasswordBean.class);
            final LocalSessionStateBean ssBean = pwmRequest.getPwmSession().getSessionStateBean();
            final UserInfoBean uiBean = pwmRequest.getPwmSession().getUserInfoBean();
            final LoginInfoBean loginBean = pwmRequest.getPwmSession().getLoginInfoBean();

            final PasswordData currentPassword = pwmRequest.readParameterAsPassword("currentPassword");

            // check the current password
            if (cpb.isCurrentPasswordRequired() && loginBean.getUserCurrentPassword() != null) {
                if (currentPassword == null) {
                    LOGGER.debug(pwmRequest, "failed password validation check: currentPassword value is missing");
                    setLastError(pwmRequest, new ErrorInformation(PwmError.ERROR_MISSING_PARAMETER));
                    return ProcessStatus.Continue;
                }

                final boolean passed;
                {
                    final boolean caseSensitive = Boolean.parseBoolean(
                            uiBean.getPasswordPolicy().getValue(PwmPasswordRule.CaseSensitive));
                    final PasswordData storedPassword = loginBean.getUserCurrentPassword();
                    passed = caseSensitive ? storedPassword.equals(currentPassword) : storedPassword.equalsIgnoreCase(currentPassword);
                }

                if (!passed) {
                    pwmRequest.getPwmApplication().getIntruderManager().convenience().markUserIdentity(
                            uiBean.getUserIdentity(), pwmRequest.getSessionLabel());
                    LOGGER.debug(pwmRequest, "failed password validation check: currentPassword value is incorrect");
                    setLastError(pwmRequest, new ErrorInformation(PwmError.ERROR_BAD_CURRENT_PASSWORD));
                    return ProcessStatus.Continue;
                }
                cpb.setCurrentPasswordPassed(true);
            }

            final List<FormConfiguration> formItem = pwmRequest.getConfig().readSettingAsForm(PwmSetting.PASSWORD_REQUIRE_FORM);

            try {
                //read the values from the request
                final Map<FormConfiguration,String> formValues = FormUtility.readFormValuesFromRequest(
                        pwmRequest, formItem, ssBean.getLocale());

                validateParamsAgainstLDAP(formValues, pwmRequest.getPwmSession(),
                        pwmRequest.getPwmSession().getSessionManager().getActor(pwmRequest.getPwmApplication()));

                cpb.setFormPassed(true);
            } catch (PwmOperationalException e) {
                pwmRequest.getPwmApplication().getIntruderManager().convenience().markAddressAndSession(pwmRequest.getPwmSession());
                pwmRequest.getPwmApplication().getIntruderManager().convenience().markUserIdentity(uiBean.getUserIdentity(), pwmRequest.getSessionLabel());
                LOGGER.debug(pwmRequest, e.getErrorInformation());
                setLastError(pwmRequest, e.getErrorInformation());
                return ProcessStatus.Continue;
            }

            return ProcessStatus.Continue;
        }
    }

    class RestCheckProgressHandler implements ProcessActionHandler {
        @Override
        public ProcessStatus processAction(final PwmRequest pwmRequest) throws ServletException, PwmUnrecoverableException, IOException {
            final ChangePasswordBean changePasswordBean = pwmRequest.getPwmApplication().getSessionStateService().getBean(pwmRequest, ChangePasswordBean.class);
            final PasswordChangeProgressChecker.ProgressTracker progressTracker = changePasswordBean.getChangeProgressTracker();
            final PasswordChangeProgressChecker.PasswordChangeProgress passwordChangeProgress;
            if (progressTracker == null) {
                passwordChangeProgress = PasswordChangeProgressChecker.PasswordChangeProgress.COMPLETE;
            } else {
                final PasswordChangeProgressChecker checker = new PasswordChangeProgressChecker(
                        pwmRequest.getPwmApplication(),
                        pwmRequest.getPwmSession().getUserInfoBean().getUserIdentity(),
                        pwmRequest.getSessionLabel(),
                        pwmRequest.getLocale()
                );
                passwordChangeProgress = checker.figureProgress(progressTracker);
            }
            final RestResultBean restResultBean = new RestResultBean();
            restResultBean.setData(passwordChangeProgress);

            LOGGER.trace(pwmRequest, "returning result for restCheckProgress: " + JsonUtil.serialize(restResultBean));
            pwmRequest.outputJsonResult(restResultBean);
            return ProcessStatus.Halt;
        }
    }


    class CompleteActionHandler implements ProcessActionHandler {
        @Override
        public ProcessStatus processAction(final PwmRequest pwmRequest) throws ServletException, PwmUnrecoverableException, IOException {
            final ChangePasswordBean cpb = pwmRequest.getPwmApplication().getSessionStateService().getBean(pwmRequest, ChangePasswordBean.class);
            final PasswordChangeProgressChecker.ProgressTracker progressTracker = cpb.getChangeProgressTracker();
            boolean isComplete = true;
            if (progressTracker != null) {
                final PasswordChangeProgressChecker checker = new PasswordChangeProgressChecker(
                        pwmRequest.getPwmApplication(),
                        pwmRequest.getPwmSession().getUserInfoBean().getUserIdentity(),
                        pwmRequest.getSessionLabel(),
                        pwmRequest.getLocale()
                );
                final PasswordChangeProgressChecker.PasswordChangeProgress passwordChangeProgress = checker.figureProgress(progressTracker);
                isComplete = passwordChangeProgress.isComplete();
            }

            if (isComplete) {
                if (progressTracker != null) {
                    final TimeDuration totalTime = TimeDuration.fromCurrent(progressTracker.getBeginTime());
                    try {
                        pwmRequest.getPwmApplication().getStatisticsManager().updateAverageValue(Statistic.AVG_PASSWORD_SYNC_TIME,totalTime.getTotalMilliseconds());
                        LOGGER.trace(pwmRequest,"password sync process marked completed (" + totalTime.asCompactString() + ")");
                    } catch (Exception e) {
                        LOGGER.error(pwmRequest,"unable to update average password sync time statistic: " + e.getMessage());
                    }
                }
                cpb.setChangeProgressTracker(null);
                final Locale locale = pwmRequest.getLocale();
                final String completeMessage = pwmRequest.getConfig().readSettingAsLocalizedString(PwmSetting.PASSWORD_COMPLETE_MESSAGE,locale);

                pwmRequest.getPwmApplication().getSessionStateService().clearBean(pwmRequest, ChangePasswordBean.class);
                if (completeMessage != null && !completeMessage.isEmpty()) {
                    final MacroMachine macroMachine = pwmRequest.getPwmSession().getSessionManager().getMacroMachine(pwmRequest.getPwmApplication());
                    final String expandedText = macroMachine.expandMacros(completeMessage);
                    pwmRequest.setAttribute(PwmRequest.Attribute.CompleteText, expandedText);
                    pwmRequest.forwardToJsp(JspUrl.PASSWORD_COMPLETE);
                } else {
                    pwmRequest.getPwmResponse().forwardToSuccessPage(Message.Success_PasswordChange);
                }
            } else {
                pwmRequest.forwardToJsp(JspUrl.PASSWORD_CHANGE_WAIT);
            }
            return ProcessStatus.Halt;
        }
    }

    private void executeChangePassword(
            final PwmRequest pwmRequest,
            final PasswordData newPassword
    )
            throws ChaiUnavailableException, PwmUnrecoverableException, PwmOperationalException
    {
        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();
        final PwmSession pwmSession = pwmRequest.getPwmSession();
        // password accepted, setup change password
        final ChangePasswordBean cpb = pwmApplication.getSessionStateService().getBean(pwmRequest, ChangePasswordBean.class);

        // change password
        PasswordUtility.setActorPassword(pwmSession, pwmApplication, newPassword);

        //init values for progress screen
        {
            final PasswordChangeProgressChecker.ProgressTracker tracker = new PasswordChangeProgressChecker.ProgressTracker();
            final PasswordChangeProgressChecker checker = new PasswordChangeProgressChecker(
                    pwmApplication,
                    pwmSession.getUserInfoBean().getUserIdentity(),
                    pwmSession.getLabel(),
                    pwmSession.getSessionStateBean().getLocale()
            );
            cpb.setChangeProgressTracker(tracker);
            cpb.setChangePasswordMaxCompletion(checker.maxCompletionTime(tracker));
        }

        // send user an email confirmation
        sendChangePasswordEmailNotice(pwmSession, pwmApplication);

        // send audit event
        pwmApplication.getAuditManager().submit(AuditEvent.CHANGE_PASSWORD, pwmSession.getUserInfoBean(), pwmSession);
    }

    void nextStep(
            final PwmRequest pwmRequest
    )
            throws IOException, PwmUnrecoverableException, ChaiUnavailableException, ServletException
    {
        final ChangePasswordBean changePasswordBean = pwmRequest.getPwmApplication().getSessionStateService().getBean(pwmRequest, ChangePasswordBean.class);

        final PwmSession pwmSession = pwmRequest.getPwmSession();
        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();
        final Configuration config = pwmApplication.getConfig();

        if (changePasswordBean.getLastError() != null) {
            pwmRequest.setResponseError(changePasswordBean.getLastError());
            changePasswordBean.setLastError(null);
        }

        if (changePasswordBean.getChangeProgressTracker() != null) {
            pwmRequest.forwardToJsp(JspUrl.PASSWORD_CHANGE_WAIT);
            return;
        }

        if (warnPageShouldBeShown(pwmRequest, changePasswordBean)) {
            LOGGER.trace(pwmRequest, "pasword expiration is within password warn period, forwarding user to warning page");
            pwmRequest.forwardToJsp(JspUrl.PASSWORD_WARN);
            return;
        }

        final String agreementMsg = pwmApplication.getConfig().readSettingAsLocalizedString(PwmSetting.PASSWORD_CHANGE_AGREEMENT_MESSAGE, pwmRequest.getLocale());
        if (agreementMsg != null && agreementMsg.length() > 0 && !changePasswordBean.isAgreementPassed()) {
            final MacroMachine macroMachine = pwmSession.getSessionManager().getMacroMachine(pwmApplication);
            final String expandedText = macroMachine.expandMacros(agreementMsg);
            pwmRequest.setAttribute(PwmRequest.Attribute.AgreementText,expandedText);
            pwmRequest.forwardToJsp(JspUrl.PASSWORD_AGREEMENT);
            return;
        }

        if (determineIfCurrentPasswordRequired(pwmApplication, pwmSession) && !changePasswordBean.isCurrentPasswordPassed()) {
            forwardToFormPage(pwmRequest);
            return;
        }

        if (!config.readSettingAsForm(PwmSetting.PASSWORD_REQUIRE_FORM).isEmpty() && !changePasswordBean.isFormPassed()) {
            forwardToFormPage(pwmRequest);
            return;
        }

        changePasswordBean.setAllChecksPassed(true);
        pwmRequest.forwardToJsp(JspUrl.PASSWORD_CHANGE);
    }

    private static boolean determineIfCurrentPasswordRequired(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession
    )
            throws PwmUnrecoverableException
    {
        final RequireCurrentPasswordMode currentSetting = pwmApplication.getConfig().readSettingAsEnum(PwmSetting.PASSWORD_REQUIRE_CURRENT, RequireCurrentPasswordMode.class);

        if (currentSetting == RequireCurrentPasswordMode.FALSE) {
            return false;
        }

        if (pwmSession.getLoginInfoBean().getType() == AuthenticationType.AUTH_FROM_PUBLIC_MODULE) {
            LOGGER.debug(pwmSession, "skipping user current password requirement, authentication type is " + AuthenticationType.AUTH_FROM_PUBLIC_MODULE);
            return false;
        }

        {
            final PasswordData currentPassword = pwmSession.getLoginInfoBean().getUserCurrentPassword();
            if (currentPassword == null) {
                LOGGER.debug(pwmSession, "skipping user current password requirement, current password is not known to application");
                return false;
            }
        }

        if (currentSetting == RequireCurrentPasswordMode.TRUE) {
            return true;
        }

        final PasswordStatus passwordStatus = pwmSession.getUserInfoBean().getPasswordState();
        return currentSetting == RequireCurrentPasswordMode.NOTEXPIRED
                && !passwordStatus.isExpired()
                && !passwordStatus.isPreExpired()
                && !passwordStatus.isViolatesPolicy()
                && !pwmSession.getUserInfoBean().isRequiresNewPassword();

    }

// -------------------------- ENUMERATIONS --------------------------

    public static void validateParamsAgainstLDAP(
            final Map<FormConfiguration, String> formValues,
            final PwmSession pwmSession,
            final ChaiUser theUser
    )
            throws ChaiUnavailableException, PwmDataValidationException
    {
        for (final FormConfiguration formItem : formValues.keySet()) {
            final String attrName = formItem.getName();
            final String value = formValues.get(formItem);
            try {
                if (!theUser.compareStringAttribute(attrName, value)) {
                    final String errorMsg = "incorrect value for '" + attrName + "'";
                    final ErrorInformation errorInfo = new ErrorInformation(PwmError.ERROR_INCORRECT_RESPONSE, errorMsg, new String[]{attrName});
                    LOGGER.debug(pwmSession, errorInfo.toDebugStr());
                    throw new PwmDataValidationException(errorInfo);
                }
                LOGGER.trace(pwmSession, "successful validation of ldap value for '" + attrName + "'");
            } catch (ChaiOperationException e) {
                LOGGER.error(pwmSession, "error during param validation of '" + attrName + "', error: " + e.getMessage());
                throw new PwmDataValidationException(new ErrorInformation(PwmError.ERROR_INCORRECT_RESPONSE, "ldap error testing value for '" + attrName + "'", new String[]{attrName}));
            }
        }
    }

    private static void sendChangePasswordEmailNotice(
            final PwmSession pwmSession,
            final PwmApplication pwmApplication
    )
            throws PwmUnrecoverableException
    {
        final Configuration config = pwmApplication.getConfig();
        final Locale locale = pwmSession.getSessionStateBean().getLocale();
        final EmailItemBean configuredEmailSetting = config.readSettingAsEmail(PwmSetting.EMAIL_CHANGEPASSWORD, locale);

        if (configuredEmailSetting == null) {
            LOGGER.debug(pwmSession, "skipping change password email for '" + pwmSession.getUserInfoBean().getUserIdentity() + "' no email configured");
            return;
        }

        pwmApplication.getEmailQueue().submitEmail(
                configuredEmailSetting,
                pwmSession.getUserInfoBean(),

                pwmSession.getSessionManager().getMacroMachine(pwmApplication));
    }

    private static void checkMinimumLifetime(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final ChangePasswordBean changePasswordBean,
            final UserInfoBean userInfoBean
    )
            throws PwmUnrecoverableException, ChaiUnavailableException, PwmOperationalException
    {
        if (changePasswordBean.isNextAllowedTimePassed()) {
            return;
        }

        try {
            PasswordUtility.checkIfPasswordWithinMinimumLifetime(
                    pwmSession.getSessionManager().getActor(pwmApplication),
                    pwmSession.getLabel(),
                    userInfoBean.getPasswordPolicy(),
                    userInfoBean.getPasswordLastModifiedTime(),
                    userInfoBean.getPasswordState()
            );
        } catch (PwmOperationalException e) {
            final boolean enforceFromForgotten = pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.CHALLENGE_ENFORCE_MINIMUM_PASSWORD_LIFETIME);
            if (!enforceFromForgotten && userInfoBean.isRequiresNewPassword()) {
                LOGGER.debug(pwmSession, "current password is too young, but skipping enforcement of minimum lifetime check due to setting "
                        + PwmSetting.CHALLENGE_ENFORCE_MINIMUM_PASSWORD_LIFETIME.toMenuLocationDebug(null, pwmSession.getSessionStateBean().getLocale()));
            } else {
                throw e;
            }
        }

        changePasswordBean.setNextAllowedTimePassed(true);
    }



    private boolean warnPageShouldBeShown(final PwmRequest pwmRequest, final ChangePasswordBean changePasswordBean) {
        final PwmSession pwmSession = pwmRequest.getPwmSession();

        if (!pwmSession.getUserInfoBean().getPasswordState().isWarnPeriod()) {
            return false;
        }

        if (pwmRequest.getPwmSession().getLoginInfoBean().isLoginFlag(LoginInfoBean.LoginFlag.skipNewPw)) {
            return false;
        }

        if (changePasswordBean.isWarnPassed()) {
            return false;
        }

        if (pwmRequest.getPwmSession().getLoginInfoBean().getAuthFlags().contains(AuthenticationType.AUTH_FROM_PUBLIC_MODULE)) {
            return false;
        }

        if (pwmRequest.getPwmSession().getLoginInfoBean().getType() == AuthenticationType.AUTH_FROM_PUBLIC_MODULE) {
            return false;
        }

        return true;
    }

    protected void forwardToFormPage(final PwmRequest pwmRequest)
            throws ServletException, PwmUnrecoverableException, IOException
    {
        pwmRequest.addFormInfoToRequestAttr(PwmSetting.PASSWORD_REQUIRE_FORM,false,false);
        pwmRequest.forwardToJsp(JspUrl.PASSWORD_FORM);
    }

    @Override
    void preProcessCheck(final PwmRequest pwmRequest) throws PwmUnrecoverableException, IOException, ServletException {
        final PwmSession pwmSession = pwmRequest.getPwmSession();
        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();
        final ChangePasswordBean changePasswordBean = pwmApplication.getSessionStateService().getBean(pwmRequest, ChangePasswordBean.class);

        if (pwmSession.getLoginInfoBean().getType() == AuthenticationType.AUTH_WITHOUT_PASSWORD) {
            throw new PwmUnrecoverableException(PwmError.ERROR_PASSWORD_REQUIRED);
        }

        if (!pwmRequest.isAuthenticated()) {
            pwmRequest.respondWithError(PwmError.ERROR_AUTHENTICATION_REQUIRED.toInfo());
            return;
        }

        if (determineIfCurrentPasswordRequired(pwmApplication, pwmSession)) {
            changePasswordBean.setCurrentPasswordRequired(true);
        }

        if (!pwmSession.getSessionManager().checkPermission(pwmApplication, Permission.CHANGE_PASSWORD)) {
            pwmRequest.respondWithError(PwmError.ERROR_UNAUTHORIZED.toInfo());
            return;
        }

        try {
            checkMinimumLifetime(pwmApplication, pwmSession, changePasswordBean, pwmSession.getUserInfoBean());
        } catch (PwmOperationalException e) {
            throw new PwmUnrecoverableException(e.getErrorInformation());
        } catch (ChaiException e) {
            throw PwmUnrecoverableException.fromChaiException(e);
        }
    }

    private void setLastError(final PwmRequest pwmRequest, final ErrorInformation errorInformation) throws PwmUnrecoverableException {
        final ChangePasswordBean changePasswordBean = pwmRequest.getPwmApplication().getSessionStateService().getBean(pwmRequest, ChangePasswordBean.class);
        changePasswordBean.setLastError(errorInformation);
        pwmRequest.setResponseError(errorInformation);
    }
}
