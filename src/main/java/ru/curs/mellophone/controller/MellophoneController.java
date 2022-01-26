package ru.curs.mellophone.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.curs.mellophone.service.MellophoneService;

import javax.validation.constraints.NotNull;


@Validated
@RestController
@CrossOrigin
@RequestMapping("/mellophone")
public class MellophoneController {

    private final MellophoneService mellophoneService;

    public MellophoneController(MellophoneService mellophoneService) {
        this.mellophoneService = mellophoneService;
    }

    @RequestMapping("/login")
    public void login(@NotNull String sesid, String gp, @NotNull String login, @NotNull String pwd, String ip) {
        mellophoneService.login(sesid, gp, login, pwd, ip);
    }

    @RequestMapping("/logout")
    public void logout(@NotNull String sesid) {
        mellophoneService.logout(sesid);
    }

    @RequestMapping({"/isauthenticated", "/checkid"})
    public String isauthenticated(@NotNull String sesid, String ip) {
        return mellophoneService.isauthenticated(sesid, ip);
    }

    @RequestMapping("/checkname")
    public String checkname(@NotNull String sesid, @NotNull String name) {
        return mellophoneService.checkname(sesid, name);
    }

    @RequestMapping("/getproviderlist")
    public String getproviderlist(String gp, @NotNull String login, @NotNull String pwd, String ip) {
        return mellophoneService.getproviderlist(gp, login, pwd, ip);
    }

    @RequestMapping("/importgroupsproviders")
    public String importgroupsproviders() {
        return mellophoneService.importgroupsproviders();
    }

    @RequestMapping("/checkcredentials")
    public String checkcredentials(String gp, @NotNull String login, @NotNull String pwd, String ip) {
        return mellophoneService.checkcredentials(gp, login, pwd, ip);
    }

    @RequestMapping("/getuserlist")
    public String getuserlist(String pid, String gp, @NotNull String token, String ip) {
        return mellophoneService.getuserlist(pid, gp, token, ip);
    }

    @PostMapping("/user/create")
    public void userCreate(@NotNull String token, @RequestBody @NotNull String user) {
        mellophoneService.userCreate(token, user);
    }

    @PostMapping("/user/{sid}")
    public void userUpdate(@NotNull String token, @PathVariable @NotNull String sid, @RequestBody @NotNull String user) {
        mellophoneService.userUpdate(token, sid, user);
    }

    @DeleteMapping("/user/{sid}")
    public void userDelete(@NotNull String token, @PathVariable @NotNull String sid) {
        mellophoneService.userDelete(token, sid);
    }

    @RequestMapping("/changepwd")
    public String changepwd(@NotNull String sesid, @NotNull String oldpwd, @NotNull String newpwd) {
        return mellophoneService.changepwd(sesid, oldpwd, newpwd);
    }

    @RequestMapping("/changeuserpwd")
    public String changeuserpwd(@NotNull String sesid, @NotNull String username, @NotNull String newpwd) {
        return mellophoneService.changeuserpwd(sesid, username, newpwd);
    }

    @RequestMapping("/changeappsesid")
    public void changeappsesid(@NotNull String oldsesid, @NotNull String newsesid) {
        mellophoneService.changeappsesid(oldsesid, newsesid);
    }


}












