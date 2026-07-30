package com.timiroom.domain.team;

import com.timiroom.domain.member.Member;
import com.timiroom.domain.member.MemberRepository;
import com.timiroom.domain.project.ProjectMember;
import com.timiroom.domain.project.ProjectRepository;
import com.timiroom.domain.project.ProjectMemberRepository;
import com.timiroom.domain.project.ProjectRole;
import com.timiroom.domain.project.ProjectService;
import com.timiroom.domain.team.dto.TeamInvitePreviewResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "rag-pipeline.base-url=http://localhost:8081",
})
class TeamServiceTest {

    @Autowired
    private TeamService teamService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @BeforeEach
    void setUp() {
        projectMemberRepository.deleteAll();
        projectRepository.deleteAll();
        teamMemberRepository.deleteAll();
        teamRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void createGeneratesInviteCodeAndOwnerMembership() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");

        assertThat(team.getInviteCode()).isNotBlank();
        TeamMember ownerMembership = teamMemberRepository.findByTeamIdAndMemberId(team.getTeamId(), owner.getMemberId())
                .orElseThrow();
        assertThat(ownerMembership.getTeamRole()).isEqualTo(TeamRole.OWNER);
    }

    @Test
    void joinByInviteCodeAcceptsTrimmedLowercaseCode() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member joiner = memberRepository.save(Member.create("joiner", "pw", "joiner@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        String inviteCode = team.getInviteCode();

        TeamMember joinedMember = teamService.joinByInviteCode(joiner.getMemberId(), "  " + inviteCode.toLowerCase() + "  ");

        assertThat(joinedMember.getTeamId()).isEqualTo(team.getTeamId());
        assertThat(joinedMember.getMemberId()).isEqualTo(joiner.getMemberId());
        assertThat(joinedMember.getTeamRole()).isEqualTo(TeamRole.MEMBER);
    }

    @Test
    void getWorkspaceReturnsMemberSummariesAndViewerRole() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member joiner = memberRepository.save(Member.create("joiner", "pw", "joiner@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(joiner.getMemberId(), team.getInviteCode());

        var workspace = teamService.getWorkspace(team.getTeamId(), joiner.getMemberId());

        assertThat(workspace.viewerRole()).isEqualTo(TeamRole.MEMBER);
        assertThat(workspace.ownerMemberId()).isEqualTo(owner.getMemberId());
        assertThat(workspace.members())
                .extracting("memberName")
                .contains("owner", "joiner");
        assertThat(workspace.team().inviteCode()).isNull();
    }

    @Test
    void getMyTeamsExposesInviteCodeOnlyToOwner() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member joiner = memberRepository.save(Member.create("joiner", "pw", "joiner@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(joiner.getMemberId(), team.getInviteCode());

        var ownerTeam = teamService.getMyTeams(owner.getMemberId()).get(0);
        var joinerTeam = teamService.getMyTeams(joiner.getMemberId()).get(0);

        assertThat(ownerTeam.inviteCode()).isEqualTo(team.getInviteCode());
        assertThat(ownerTeam.viewerRole()).isEqualTo(TeamRole.OWNER);
        assertThat(joinerTeam.inviteCode()).isNull();
        assertThat(joinerTeam.viewerRole()).isEqualTo(TeamRole.MEMBER);
    }

    @Test
    void transferOwnershipMovesOwnerRole() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member joiner = memberRepository.save(Member.create("joiner", "pw", "joiner@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(joiner.getMemberId(), team.getInviteCode());

        teamService.transferOwnership(team.getTeamId(), owner.getMemberId(), joiner.getMemberId());

        TeamMember ownerMembership = teamMemberRepository.findByTeamIdAndMemberId(team.getTeamId(), owner.getMemberId())
                .orElseThrow();
        TeamMember joinerMembership = teamMemberRepository.findByTeamIdAndMemberId(team.getTeamId(), joiner.getMemberId())
                .orElseThrow();

        assertThat(ownerMembership.getTeamRole()).isEqualTo(TeamRole.MEMBER);
        assertThat(joinerMembership.getTeamRole()).isEqualTo(TeamRole.OWNER);
    }

    @Test
    void removeMemberClearsProjectMemberships() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member joiner = memberRepository.save(Member.create("joiner", "pw", "joiner@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(joiner.getMemberId(), team.getInviteCode());

        var project = projectService.create(team.getTeamId(), owner.getMemberId(), "Project A", "desc");
        projectService.addMember(project.getProjectId(), owner.getMemberId(), joiner.getMemberId(), ProjectRole.FRONTEND);
        assertThat(projectMemberRepository.existsByProjectIdAndMemberId(project.getProjectId(), joiner.getMemberId())).isTrue();

        teamService.removeMember(team.getTeamId(), owner.getMemberId(), joiner.getMemberId());

        assertThat(teamMemberRepository.findByTeamIdAndMemberId(team.getTeamId(), joiner.getMemberId())).isEmpty();
        assertThat(projectMemberRepository.existsByProjectIdAndMemberId(project.getProjectId(), joiner.getMemberId())).isFalse();
    }

    @Test
    void removeMemberPromotesRemainingProjectMemberToPm() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member projectManager = memberRepository.save(Member.create("pm", "pw", "pm@test.com"));
        Member collaborator = memberRepository.save(Member.create("collab", "pw", "collab@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(projectManager.getMemberId(), team.getInviteCode());
        teamService.joinByInviteCode(collaborator.getMemberId(), team.getInviteCode());

        var project = projectService.create(team.getTeamId(), projectManager.getMemberId(), "Project A", "desc");
        projectService.addMember(project.getProjectId(), projectManager.getMemberId(), collaborator.getMemberId(), ProjectRole.FRONTEND);

        teamService.removeMember(team.getTeamId(), owner.getMemberId(), projectManager.getMemberId());

        ProjectMember collaboratorMembership = projectMemberRepository
                .findByProjectIdAndMemberId(project.getProjectId(), collaborator.getMemberId())
                .orElseThrow();
        assertThat(collaboratorMembership.getProjectRole()).isEqualTo(ProjectRole.PM);
    }

    @Test
    void leaveTeamAssignsOwnerToProjectWhenLastProjectMemberLeaves() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member joiner = memberRepository.save(Member.create("joiner", "pw", "joiner@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(joiner.getMemberId(), team.getInviteCode());

        var project = projectService.create(team.getTeamId(), joiner.getMemberId(), "Project A", "desc");

        teamService.leaveTeam(team.getTeamId(), joiner.getMemberId());

        assertThat(projectMemberRepository.findByProjectIdAndMemberId(project.getProjectId(), joiner.getMemberId())).isEmpty();
        ProjectMember ownerMembership = projectMemberRepository
                .findByProjectIdAndMemberId(project.getProjectId(), owner.getMemberId())
                .orElseThrow();
        assertThat(ownerMembership.getProjectRole()).isEqualTo(ProjectRole.PM);
    }

    @Test
    void deleteTeamRemovesWorkspaceMembershipsAndProjects() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member joiner = memberRepository.save(Member.create("joiner", "pw", "joiner@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(joiner.getMemberId(), team.getInviteCode());

        var project = projectService.create(team.getTeamId(), owner.getMemberId(), "Project A", "desc");
        projectService.addMember(project.getProjectId(), owner.getMemberId(), joiner.getMemberId(), ProjectRole.FRONTEND);

        teamService.deleteTeam(team.getTeamId(), owner.getMemberId());

        assertThat(teamRepository.findById(team.getTeamId())).isEmpty();
        assertThat(teamMemberRepository.findByTeamId(team.getTeamId())).isEmpty();
        assertThat(projectRepository.findById(project.getProjectId())).isEmpty();
        assertThat(projectMemberRepository.findByProjectId(project.getProjectId())).isEmpty();
    }

    @Test
    void leaveTeamRejectsOwnerWithoutTransfer() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");

        assertThatThrownBy(() -> teamService.leaveTeam(team.getTeamId(), owner.getMemberId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("오너는 먼저 권한을 이전해야 합니다");
    }

    @Test
    void joinByInviteCodeRejectsBlankCode() {
        Member joiner = memberRepository.save(Member.create("joiner", "pw", "joiner@test.com"));

        assertThatThrownBy(() -> teamService.joinByInviteCode(joiner.getMemberId(), "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("초대 코드가 필요합니다");
    }

    @Test
    void getInvitePreviewReturnsPublicWorkspaceSummary() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");

        TeamInvitePreviewResponse preview = teamService.getInvitePreview(team.getInviteCode());

        assertThat(preview.teamId()).isEqualTo(team.getTeamId());
        assertThat(preview.teamName()).isEqualTo("Alpha Team");
        assertThat(preview.description()).isEqualTo("sample team");
        assertThat(preview.inviteCode()).isEqualTo(team.getInviteCode());
        assertThat(preview.ownerName()).isEqualTo("owner");
        assertThat(preview.memberCount()).isEqualTo(1L);
    }

    @Test
    void updateMemberRoleChangesRoleBetweenMemberAndGuest() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member joiner = memberRepository.save(Member.create("joiner", "pw", "joiner@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(joiner.getMemberId(), team.getInviteCode());

        teamService.updateMemberRole(team.getTeamId(), owner.getMemberId(), joiner.getMemberId(), TeamRole.GUEST);
        TeamMember guest = teamMemberRepository.findByTeamIdAndMemberId(team.getTeamId(), joiner.getMemberId()).orElseThrow();
        assertThat(guest.getTeamRole()).isEqualTo(TeamRole.GUEST);

        teamService.updateMemberRole(team.getTeamId(), owner.getMemberId(), joiner.getMemberId(), TeamRole.MEMBER);
        TeamMember member = teamMemberRepository.findByTeamIdAndMemberId(team.getTeamId(), joiner.getMemberId()).orElseThrow();
        assertThat(member.getTeamRole()).isEqualTo(TeamRole.MEMBER);
    }

    @Test
    void updateMemberRoleRejectsOwnerRoleAssignment() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member joiner = memberRepository.save(Member.create("joiner", "pw", "joiner@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(joiner.getMemberId(), team.getInviteCode());

        assertThatThrownBy(() ->
                teamService.updateMemberRole(team.getTeamId(), owner.getMemberId(), joiner.getMemberId(), TeamRole.OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이전(transfer)");
    }

    @Test
    void guestCannotCreateProject() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member guest = memberRepository.save(Member.create("guest", "pw", "guest@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(guest.getMemberId(), team.getInviteCode());
        teamService.updateMemberRole(team.getTeamId(), owner.getMemberId(), guest.getMemberId(), TeamRole.GUEST);

        assertThatThrownBy(() ->
                projectService.create(team.getTeamId(), guest.getMemberId(), "Blocked Project", "desc"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("멤버 이상 권한");
    }

    @Test
    void memberCanCreateProject() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member member = memberRepository.save(Member.create("member", "pw", "member@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(member.getMemberId(), team.getInviteCode());

        var project = projectService.create(team.getTeamId(), member.getMemberId(), "Allowed Project", "desc");
        assertThat(project).isNotNull();
        assertThat(project.getProjectName()).isEqualTo("Allowed Project");
    }

    @Test
    void onlyTeamOwnerCanDeleteProject() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member member = memberRepository.save(Member.create("member", "pw", "member@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(member.getMemberId(), team.getInviteCode());

        var project = projectService.create(team.getTeamId(), member.getMemberId(), "Project A", "desc");

        assertThatThrownBy(() ->
                projectService.delete(project.getProjectId(), member.getMemberId()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("오너 권한");

        assertThatCode(() ->
                projectService.delete(project.getProjectId(), owner.getMemberId()))
                .doesNotThrowAnyException();
    }

    @Test
    void nonProjectMemberCannotSaveDocument() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member outsider = memberRepository.save(Member.create("outsider", "pw", "outsider@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(outsider.getMemberId(), team.getInviteCode());

        var project = projectService.create(team.getTeamId(), owner.getMemberId(), "Project A", "desc");

        assertThatThrownBy(() ->
                projectService.saveDocument(project.getProjectId(), outsider.getMemberId(),
                        com.timiroom.domain.pipeline.PipelineArtifact.ArtifactType.PRD, "content"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("프로젝트 멤버만");
    }

    @Test
    void backendCanEditDbSchemaButNotPrd() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member dev = memberRepository.save(Member.create("dev", "pw", "dev@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(dev.getMemberId(), team.getInviteCode());

        var project = projectService.create(team.getTeamId(), owner.getMemberId(), "Project A", "desc");
        projectService.addMember(project.getProjectId(), owner.getMemberId(), dev.getMemberId(), ProjectRole.BACKEND);

        // BACKEND cannot edit PRD
        assertThatThrownBy(() ->
                projectService.saveDocument(project.getProjectId(), dev.getMemberId(),
                        com.timiroom.domain.pipeline.PipelineArtifact.ArtifactType.PRD, "content"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("BACKEND");

        // BACKEND can pass DB_SCHEMA permission check (fails later on missing pipeline data, not SecurityException)
        assertThatThrownBy(() ->
                projectService.saveDocument(project.getProjectId(), dev.getMemberId(),
                        com.timiroom.domain.pipeline.PipelineArtifact.ArtifactType.DB_SCHEMA, "content"))
                .isNotInstanceOf(SecurityException.class);
    }

    @Test
    void designerCanEditPrdButNotDbSchema() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member designer = memberRepository.save(Member.create("designer", "pw", "designer@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(designer.getMemberId(), team.getInviteCode());

        var project = projectService.create(team.getTeamId(), owner.getMemberId(), "Project A", "desc");
        projectService.addMember(project.getProjectId(), owner.getMemberId(), designer.getMemberId(), ProjectRole.DESIGNER);

        // DESIGNER cannot edit DB_SCHEMA
        assertThatThrownBy(() ->
                projectService.saveDocument(project.getProjectId(), designer.getMemberId(),
                        com.timiroom.domain.pipeline.PipelineArtifact.ArtifactType.DB_SCHEMA, "content"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("DESIGNER");

        // DESIGNER can pass PRD permission check
        assertThatThrownBy(() ->
                projectService.saveDocument(project.getProjectId(), designer.getMemberId(),
                        com.timiroom.domain.pipeline.PipelineArtifact.ArtifactType.PRD, "content"))
                .isNotInstanceOf(SecurityException.class);
    }

    @Test
    void removeMemberRejectedForNonOwner() {
        Member owner = memberRepository.save(Member.create("owner", "pw", "owner@test.com"));
        Member memberA = memberRepository.save(Member.create("memberA", "pw", "a@test.com"));
        Member memberB = memberRepository.save(Member.create("memberB", "pw", "b@test.com"));

        Team team = teamService.create(owner.getMemberId(), "Alpha Team", "sample team");
        teamService.joinByInviteCode(memberA.getMemberId(), team.getInviteCode());
        teamService.joinByInviteCode(memberB.getMemberId(), team.getInviteCode());

        assertThatThrownBy(() ->
                teamService.removeMember(team.getTeamId(), memberA.getMemberId(), memberB.getMemberId()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("오너 권한");
    }
}
